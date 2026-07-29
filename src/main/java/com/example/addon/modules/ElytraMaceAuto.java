package com.yourname.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.item.ElytraItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Comparator;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraMaceAuto extends Module {
    // ----- НАСТРОЙКИ -----
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombat = settings.createGroup("Combat");
    private final SettingGroup sgFlight = settings.createGroup("Flight");

    // Общие
    private final Setting<Double> searchRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("search-range")
        .description("Радиус поиска игроков в чанках")
        .defaultValue(64)
        .min(16)
        .max(128)
        .build()
    );

    private final Setting<Double> engageRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("engage-range")
        .description("Дистанция начала атаки (переключение на булаву)")
        .defaultValue(10)
        .min(3)
        .max(20)
        .build()
    );

    // Бой
    private final Setting<Double> attackHeight = sgCombat.add(new DoubleSetting.Builder()
        .name("attack-height")
        .description("Высота над игроком для атаки")
        .defaultValue(8)
        .min(3)
        .max(20)
        .build()
    );

    private final Setting<Double> switchHeight = sgCombat.add(new DoubleSetting.Builder()
        .name("switch-height")
        .description("Высота, на которой менять элитры на нагрудник")
        .defaultValue(3)
        .min(1)
        .max(10)
        .build()
    );

    private final Setting<Boolean> autoRocket = sgFlight.add(new BoolSetting.Builder()
        .name("auto-rocket")
        .description("Автоматически использовать ракеты для подъёма")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rocketCount = sgFlight.add(new IntSetting.Builder()
        .name("rocket-count")
        .description("Количество ракет для старта")
        .defaultValue(2)
        .min(1)
        .max(5)
        .build()
    );

    private final Setting<Double> flightSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("flight-speed")
        .description("Скорость полёта к цели")
        .defaultValue(2.5)
        .min(1.0)
        .max(5.0)
        .build()
    );

    // ----- ПЕРЕМЕННЫЕ СОСТОЯНИЯ -----
    private enum State { IDLE, LAUNCHING, FLYING_TO_TARGET, DIVING, ATTACKING, RESETTING }
    private State currentState = State.IDLE;
    
    private PlayerEntity target = null;
    private Vec3d targetPos = null;
    private int rocketTimer = 0;
    private int stateTimer = 0;
    private boolean hasSwitchedChest = false;
    private boolean hasHit = false;
    private boolean isFlyingUp = false;
    private Vec3d lastPos = Vec3d.ZERO;

    public ElytraMaceAuto() {
        super(Categories.Combat, "elytra-mace-auto", "Автопилот для связки элитра + булава");
    }

    @Override
    public void onActivate() {
        currentState = State.IDLE;
        target = null;
        targetPos = null;
        rocketTimer = 0;
        stateTimer = 0;
        hasSwitchedChest = false;
        hasHit = false;
        isFlyingUp = false;
    }

    @Override
    public void onDeactivate() {
        // Сброс камеры и управлений
        if (mc.player != null) {
            mc.player.setYaw(mc.player.getYaw());
            mc.player.setPitch(0);
        }
        // Отключаем полёт
        if (mc.options != null) {
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Поиск цели в радиусе загруженных чанков
        if (target == null || target.isDead() || target.getHealth() <= 0) {
            findTarget();
            if (target == null) {
                currentState = State.IDLE;
                return;
            }
        }

        // Обновляем позицию цели
        targetPos = target.getPos();
        double distance = mc.player.getPos().distanceTo(targetPos);
        double heightDiff = mc.player.getPos().y - targetPos.y;

        // ----- МАШИНА СОСТОЯНИЙ -----
        switch (currentState) {
            case IDLE:
                // Начинаем атаку если цель в радиусе
                if (distance <= searchRange.get()) {
                    currentState = State.LAUNCHING;
                    stateTimer = 0;
                    rocketTimer = 0;
                    info("Атака на " + target.getName().getString());
                }
                break;

            case LAUNCHING:
                // 1. Два прыжка для взлёта
                if (mc.player.isOnGround()) {
                    mc.options.jumpKey.setPressed(true);
                } else {
                    mc.options.jumpKey.setPressed(false);
                    // Если в воздухе - надеваем элитры
                    if (!hasElytra()) {
                        equipElytra();
                    }
                    // Используем ракеты для старта
                    if (autoRocket.get() && rocketTimer < rocketCount.get()) {
                        useRocket();
                        rocketTimer++;
                    }
                    // Взлетаем вверх
                    if (rocketTimer >= rocketCount.get()) {
                        currentState = State.FLYING_TO_TARGET;
                        isFlyingUp = true;
                        stateTimer = 0;
                        info("Взлёт завершён, лечу к цели");
                    }
                }
                break;

            case FLYING_TO_TARGET:
                // Летим к цели на высоту attackHeight над ней
                double targetY = targetPos.y + attackHeight.get();
                Vec3d targetPos3d = new Vec3d(targetPos.x, targetY, targetPos.z);
                
                // Если достигли нужной высоты и дистанции
                if (isFlyingUp && mc.player.getPos().y >= targetY - 1) {
                    isFlyingUp = false;
                    // Переходим в пикирование
                    currentState = State.DIVING;
                    stateTimer = 0;
                    hasSwitchedChest = false;
                    info("Пикирую на цель!");
                }

                // Автопилот к позиции
                flyTo(targetPos3d);
                
                // Поворачиваем камеру к цели (с небольшим смещением вверх)
                lookAt(targetPos.x, targetY, targetPos.z, 5);
                break;

            case DIVING:
                // Пикируем вниз, меняем элитры на высоте switchHeight
                double currentHeight = mc.player.getPos().y - targetPos.y;
                
                // Если высота меньше switchHeight - меняем элитры на нагрудник
                if (currentHeight <= switchHeight.get() && !hasSwitchedChest) {
                    switchChestplate();
                    hasSwitchedChest = true;
                    // Переключаемся на булаву
                    switchToMace();
                    info("Сменил элитры, булава в руке!");
                }

                // Поворачиваем камеру строго вниз (-90 градусов) для удара
                if (hasSwitchedChest) {
                    mc.player.setPitch(90);
                    // Поворачиваем к цели для прицела
                    lookAt(targetPos.x, targetPos.y, targetPos.z, 0);
                }

                // Если прилетели к цели (дистанция < engageRange) - атакуем
                if (distance <= engageRange.get() && hasSwitchedChest) {
                    currentState = State.ATTACKING;
                    stateTimer = 0;
                    hasHit = false;
                    info("АТАКА!");
                }

                // Если упали ниже цели - перезапуск
                if (mc.player.getPos().y < targetPos.y - 2) {
                    currentState = State.RESETTING;
                    stateTimer = 0;
                    info("Промах, перезапуск...");
                }
                break;

            case ATTACKING:
                // Бьём булавой
                if (!hasHit && distance <= engageRange.get()) {
                    // Атака через правый клик (удар булавой)
                    mc.options.attackKey.setPressed(true);
                    hasHit = true;
                    
                    // После удара - быстрый поворот камеры вверх (-90)
                    mc.player.setPitch(-90);
                    
                    // Проверяем жив ли игрок
                    if (target.isDead() || target.getHealth() <= 0) {
                        info("Цель убита!");
                        currentState = State.IDLE;
                        target = null;
                        mc.options.attackKey.setPressed(false);
                        return;
                    }
                }

                // Если цель жива - перезапускаем цикл
                if (hasHit && stateTimer > 20) { // 1 секунда на оценку
                    currentState = State.RESETTING;
                    stateTimer = 0;
                    info("Цель жива, повторная атака...");
                }
                stateTimer++;
                break;

            case RESETTING:
                // Поднимаемся вверх для новой атаки
                mc.options.jumpKey.setPressed(true);
                
                // Если набрали высоту - перезапуск
                if (mc.player.getPos().y >= targetPos.y + attackHeight.get()) {
                    currentState = State.FLYING_TO_TARGET;
                    isFlyingUp = false;
                    hasSwitchedChest = false;
                    stateTimer = 0;
                    info("Новый заход!");
                }
                break;
        }

        stateTimer++;
    }

    // ----- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ -----

    private void findTarget() {
        double range = searchRange.get();
        Box box = new Box(
            mc.player.getX() - range, mc.player.getY() - range, mc.player.getZ() - range,
            mc.player.getX() + range, mc.player.getY() + range, mc.player.getZ() + range
        );

        target = mc.world.getPlayers().stream()
            .filter(p -> p != mc.player && p.isAlive())
            .filter(p -> p.getPos().distanceTo(mc.player.getPos()) <= range)
            .min(Comparator.comparingDouble(p -> p.getPos().distanceTo(mc.player.getPos())))
            .orElse(null);
    }

    private void flyTo(Vec3d target) {
        Vec3d current = mc.player.getPos();
        Vec3d diff = target.subtract(current);
        
        double length = diff.length();
        if (length < 0.1) return;

        // Нормализуем направление
        Vec3d dir = diff.normalize().multiply(flightSpeed.get() / 3);
        
        // Добавляем вертикальную скорость при подъёме
        if (isFlyingUp) {
            dir = dir.add(0, 0.5, 0);
        }

        // Применяем движение
        mc.player.setVelocity(dir);
        
        // Поворачиваем в направлении движения
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        mc.player.setYaw(yaw);
    }

    private void lookAt(double x, double y, double z, float pitchOffset) {
        ClientPlayerEntity player = mc.player;
        Vec3d targetVec = new Vec3d(x, y, z);
        Vec3d eyePos = player.getEyePos();
        
        Vec3d diff = targetVec.subtract(eyePos);
        double distance = diff.length();
        
        if (distance < 0.001) return;
        
        float yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float pitch = (float) Math.toDegrees(Math.asin(diff.y / distance));
        
        player.setYaw(yaw);
        player.setPitch(pitch + pitchOffset);
    }

    private void useRocket() {
        // Ищем ракету (firework) в инвентаре
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.FIREWORK_ROCKET) {
                mc.player.getInventory().selectedSlot = i;
                // Используем правый клик
                mc.options.useKey.setPressed(true);
                // Сбрасываем через тик
                new Thread(() -> {
                    try { Thread.sleep(50); } catch (Exception e) {}
                    mc.options.useKey.setPressed(false);
                }).start();
                break;
            }
        }
    }

    private boolean hasElytra() {
        ItemStack chest = mc.player.getInventory().getArmorStack(2);
        return chest.getItem() instanceof ElytraItem;
    }

    private void equipElytra() {
        // Ищем элитры в инвентаре
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof ElytraItem) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId, 
                    i, 0, 
                    SlotActionType.QUICK_MOVE, 
                    mc.player
                );
                break;
            }
        }
    }

    private void switchChestplate() {
        // Ищем нагрудник в инвентаре
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof net.minecraft.item.ArmorItem && 
                ((net.minecraft.item.ArmorItem) stack.getItem()).getType() == net.minecraft.item.ArmorItem.Type.CHESTPLATE) {
                
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId, 
                    i, 0, 
                    SlotActionType.QUICK_MOVE, 
                    mc.player
                );
                break;
            }
        }
    }

    private void switchToMace() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.HEAVY_CORE) {
                mc.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }

    @Override
    public String getInfoString() {
        if (target != null) {
            return String.format("§a%s§r | §b%.1fм", 
                target.getName().getString(), 
                target.getPos().distanceTo(mc.player.getPos())
            );
        }
        return "§cIDLE";
    }
                      }
