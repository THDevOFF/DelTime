package ru.tcd3v.deltime.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

public class DeltimeClient implements ClientModInitializer {

    private static KeyBinding toggleKeyBinding;
    private static KeyBinding toggleModeKeyBinding;

    public static boolean isEnabled = true;
    public static DisplayMode displayMode = DisplayMode.ALWAYS;

    public enum DisplayMode {
        ALWAYS("Всегда"),
        LOOKING_AT("При наведении");

        private final String displayName;

        DisplayMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public DisplayMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    @Override
    public void onInitializeClient() {
        // Регистрируем клавишу для включения/выключения мода
        toggleKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.itemtimer.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.itemtimer.general"
        ));

        // Регистрируем клавишу для переключения режима отображения
        toggleModeKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.itemtimer.toggle_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.itemtimer.general"
        ));

        WorldRenderEvents.AFTER_ENTITIES.register((context) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;

            // Горячие клавиши
            while (toggleKeyBinding.wasPressed()) {
                isEnabled = !isEnabled;
                client.player.sendMessage(Text.literal("Item Timer: " + (isEnabled ? "Включен" : "Выключен")), true);

                // При выключении сразу скрываем все кастомные имена поблизости
                if (!isEnabled) {
                    client.world.getEntitiesByClass(ItemEntity.class,
                            client.player.getBoundingBox().expand(64), e -> true
                    ).forEach(e -> {
                        e.setCustomNameVisible(false);
                        // по желанию: e.setCustomName(null);
                    });
                }
            }
            while (toggleModeKeyBinding.wasPressed()) {
                displayMode = displayMode.next();
                client.player.sendMessage(Text.literal("Режим отображения: " + displayMode.getDisplayName()), true);
            }

            if (!isEnabled) return;

            // Итерируем ВСЕ ItemEntity поблизости — без фильтра по режиму,
            // чтобы иметь возможность скрывать имена, когда взгляд уходит
            client.world.getEntitiesByClass(ItemEntity.class,
                    client.player.getBoundingBox().expand(64), e -> true
            ).forEach(itemEntity -> {
                // Считаем остаток времени жизни
                int age = itemEntity.getItemAge();
                int maxAge = 6000; // 5 минут
                int remainingTicks = Math.max(0, maxAge - age);

                if (remainingTicks <= 0) {
                    // Время вышло — убираем кастомное имя и его видимость
                    itemEntity.setCustomName(null);
                    itemEntity.setCustomNameVisible(false);
                    return;
                }

                int totalSeconds = remainingTicks / 20;
                int minutes = totalSeconds / 60;
                int seconds = totalSeconds % 60;
                String timerText = String.format("%d:%02d", minutes, seconds);

                // Формируем имя-таймер (клиентская подмена)
                itemEntity.setCustomName(Text.literal(timerText));

                // Решаем, показывать ли его
                boolean looking = isPlayerLookingAtItem(client, itemEntity);
                boolean show =
                        (displayMode == DisplayMode.ALWAYS) ||
                                (displayMode == DisplayMode.LOOKING_AT && looking);

                // ВАЖНО: если не смотрим — просто скрываем (visible=false), но имя оставляем
                itemEntity.setCustomNameVisible(show);
            });
        });

    }

    private boolean isPlayerLookingAtItem(MinecraftClient client, ItemEntity itemEntity) {
        if (client.player == null) return false;

        Vec3d playerEyePos = client.player.getEyePos();
        Vec3d playerLookVec = client.player.getRotationVec(1.0f);
        Vec3d itemPos = itemEntity.getPos().add(0, itemEntity.getHeight() / 2, 0);

        // Вычисляем вектор от игрока к предмету
        Vec3d toItem = itemPos.subtract(playerEyePos).normalize();

        // Вычисляем угол между направлением взгляда и направлением к предмету
        double dotProduct = playerLookVec.dotProduct(toItem);
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct)));

        // Расстояние до предмета
        double distance = playerEyePos.distanceTo(itemPos);

        // Динамический угол обзора в зависимости от расстояния
        double maxAngle = Math.toRadians(15.0); // базовый угол 15 градусов
        if (distance > 10) {
            maxAngle = Math.toRadians(8.0); // уменьшаем угол для дальних предметов
        }

        // Проверяем, находится ли предмет в конусе взгляда и не дальше 32 блоков
        return angle <= maxAngle && distance <= 32.0;
    }

    private void renderItemNameAndTimer(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                        ItemEntity itemEntity, Vec3d cameraPos,
                                        float tickDelta) {

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();

        // Позиция предмета
        double x = MathHelper.lerp(tickDelta, itemEntity.lastRenderX, itemEntity.getX()) - cameraPos.x;
        double y = MathHelper.lerp(tickDelta, itemEntity.lastRenderY, itemEntity.getY()) - cameraPos.y + itemEntity.getHeight() + 0.5;
        double z = MathHelper.lerp(tickDelta, itemEntity.lastRenderZ, itemEntity.getZ()) - cameraPos.z;

        double distance = Math.sqrt(x * x + y * y + z * z);
        if (distance > 64.0) return;

        matrices.push();
        matrices.translate(x, y, z);
        matrices.multiply(dispatcher.getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Считаем таймер
        int age = itemEntity.getItemAge();
        int maxAge = 6000; // 5 минут = 6000 тиков
        int remainingTicks = Math.max(0, maxAge - age);

        if (remainingTicks <= 0) {
            matrices.pop();
            return;
        }

        int totalSeconds = remainingTicks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;

        String timerText = String.format("%d:%02d", minutes, seconds);
        Text timerDisplay = Text.literal(timerText);

        float timerWidth = textRenderer.getWidth(timerDisplay);

        int timerColor = getTimerColor(remainingTicks, maxAge);

        // Рисуем только таймер (без имени и количества)
        textRenderer.draw(timerDisplay,
                -timerWidth / 2f, -20,
                timerColor, false,
                matrix, vertexConsumers,
                TextRenderer.TextLayerType.NORMAL,
                0x40000000, 15728880);

        matrices.pop();
    }

    private int getTimerColor(int remainingTicks, int maxAge) {
        float ratio = (float) remainingTicks / maxAge;

        if (ratio > 0.5f) {
            return 0x55FF55; // Зеленый
        } else if (ratio > 0.25f) {
            return 0xFFFF55; // Желтый
        } else {
            return 0xFF5555; // Красный
        }
    }
}
