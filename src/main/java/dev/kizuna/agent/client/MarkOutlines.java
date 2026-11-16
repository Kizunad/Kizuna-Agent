package dev.kizuna.agent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kizuna.agent.AgentMod;
import dev.kizuna.agent.bridge.BridgeHost;
import dev.kizuna.agent.marks.Marks;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;

import java.util.List;

/**
 * 把指点杖标的点画在世界里：每个标的方块描一圈框、上面浮着编号；正好标了两个点时，再画出这两点围成的长方体。
 *
 * <p>单人世界里客户端和内置服务端在同一个进程，标记直接读桥接那份的快照，不走网络。</p>
 */
final class MarkOutlines {
    /** 框比方块略大一点，不和方块表面叠在一起闪。 */
    private static final float GROW = 0.004F;
    private static final float[] MARK_COLOR = {0.15F, 0.85F, 0.95F, 0.9F};
    private static final float[] SPAN_COLOR = {1.0F, 0.85F, 0.25F, 0.9F};
    private static final float LABEL_SCALE = 0.03F;
    private static final float LABEL_LIFT = 1.35F;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int LABEL_BACKGROUND = 0x80000000;
    private static final int NO_OUTLINE = 0;
    /** 长方体的 12 条棱：每条是两个角，角用三位 0/1 表示取小角还是大角的 x、y、z。 */
    private static final int[][] EDGES = {
            {0b000, 0b100}, {0b010, 0b110}, {0b001, 0b101}, {0b011, 0b111},
            {0b000, 0b010}, {0b100, 0b110}, {0b001, 0b011}, {0b101, 0b111},
            {0b000, 0b001}, {0b100, 0b101}, {0b010, 0b011}, {0b110, 0b111}};

    private MarkOutlines() {
        // 只有静态绘制方法。
    }

    static void submit(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        List<Marks.Mark> all = AgentMod.host().map(BridgeHost::marks).map(Marks::list).orElse(List.of());
        if (all.isEmpty()) {
            return;
        }
        CameraRenderState camera = context.levelState().cameraRenderState;
        float width = minecraft.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
        PoseStack matrices = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        for (int index = 0; index < all.size(); index++) {
            Marks.Mark mark = all.get(index);
            if (!mark.dimension().equals(dimension)) {
                continue;
            }
            box(matrices, collector, camera, new int[] {mark.x(), mark.y(), mark.z()},
                    new int[] {mark.x() + 1, mark.y() + 1, mark.z() + 1}, MARK_COLOR, width);
            label(matrices, collector, camera, mark, "#" + (index + 1));
        }
        if (all.size() == 2 && all.stream().allMatch(mark -> mark.dimension().equals(dimension))) {
            Marks.Mark first = all.get(0);
            Marks.Mark second = all.get(1);
            box(matrices, collector, camera,
                    new int[] {Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z())},
                    new int[] {Math.max(first.x(), second.x()) + 1, Math.max(first.y(), second.y()) + 1, Math.max(first.z(), second.z()) + 1},
                    SPAN_COLOR, width);
        }
    }

    private static void box(PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera, int[] min, int[] max,
                            float[] color, float width) {
        // 坐标相对相机；先收集、后统一画。
        matrices.pushPose();
        matrices.translate(min[0] - camera.pos.x, min[1] - camera.pos.y, min[2] - camera.pos.z);
        float[] size = {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
        collector.submitCustomGeometry(matrices, RenderTypes.lines(), (pose, buffer) -> {
            for (int[] edge : EDGES) {
                float[] from = corner(edge[0], size);
                float[] to = corner(edge[1], size);
                float length = Math.abs(to[0] - from[0]) + Math.abs(to[1] - from[1]) + Math.abs(to[2] - from[2]);
                float[] normal = {(to[0] - from[0]) / length, (to[1] - from[1]) / length, (to[2] - from[2]) / length};
                vertex(pose, buffer, from, normal, color, width);
                vertex(pose, buffer, to, normal, color, width);
            }
        });
        matrices.popPose();
    }

    private static float[] corner(int bits, float[] size) {
        return new float[] {(bits & 0b100) == 0 ? -GROW : size[0] + GROW, (bits & 0b010) == 0 ? -GROW : size[1] + GROW,
                (bits & 0b001) == 0 ? -GROW : size[2] + GROW};
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float[] point, float[] normal, float[] color, float width) {
        // 线条顶点的法线是线段方向，着色器据此在屏幕上加宽。
        buffer.addVertex(pose, point[0], point[1], point[2]).setColor(color[0], color[1], color[2], color[3])
                .setNormal(pose, normal[0], normal[1], normal[2]).setLineWidth(width);
    }

    private static void label(PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera, Marks.Mark mark, String text) {
        // 和原版名牌一样：相机相对、面向相机、Y 轴向下。
        Font font = Minecraft.getInstance().font;
        FormattedCharSequence line = Component.literal(text).getVisualOrderText();
        matrices.pushPose();
        matrices.translate(mark.x() + 0.5 - camera.pos.x, mark.y() + LABEL_LIFT - camera.pos.y, mark.z() + 0.5 - camera.pos.z);
        matrices.mulPose(camera.orientation);
        matrices.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        collector.submitText(matrices, -font.width(line) / 2.0F, 0, line, false /* shadow */, Font.DisplayMode.SEE_THROUGH,
                LightCoordsUtil.FULL_BRIGHT, LABEL_COLOR, LABEL_BACKGROUND, NO_OUTLINE);
        matrices.popPose();
    }
}
