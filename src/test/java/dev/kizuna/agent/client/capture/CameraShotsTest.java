package dev.kizuna.agent.client.capture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.agent.bridge.BridgeException;
import dev.kizuna.agent.client.capture.CameraShots.Plan;
import dev.kizuna.agent.client.capture.CameraShots.Pose;
import dev.kizuna.agent.client.capture.CameraShots.Shot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraShotsTest {
    private static final double TOLERANCE = 1e-4;

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static Pose pose(Shot shot) {
        return shot.pose().orElseThrow();
    }

    @Test
    void noArgumentsMeansThePlayersOwnViewWithoutHud() {
        Plan plan = CameraShots.plan(json("{}"));
        assertEquals(1, plan.shots().size());
        assertTrue(plan.shots().getFirst().pose().isEmpty());
        assertFalse(plan.shots().getFirst().hud());
        assertEquals(CameraShots.DEFAULT_WIDTH, plan.width());
        assertTrue(CameraShots.plan(json("{\"hud\": true}")).shots().getFirst().hud());
    }

    @Test
    void lookingAtTurnsTowardsTheTargetInGameAngles() {
        // 游戏里的朝向：yaw 0 朝南（+z）、90 朝西（-x）、180 朝北、-90 朝东；pitch 正数低头。
        double[] here = {0, 64, 0};
        assertEquals(0, CameraShots.lookingAt(here, new double[] {0, 64, 10}, OptionalDouble.empty()).yaw(), TOLERANCE);
        assertEquals(90, CameraShots.lookingAt(here, new double[] {-10, 64, 0}, OptionalDouble.empty()).yaw(), TOLERANCE);
        assertEquals(180, Math.abs(CameraShots.lookingAt(here, new double[] {0, 64, -10}, OptionalDouble.empty()).yaw()), TOLERANCE);
        assertEquals(-90, CameraShots.lookingAt(here, new double[] {10, 64, 0}, OptionalDouble.empty()).yaw(), TOLERANCE);
        assertEquals(45, CameraShots.lookingAt(here, new double[] {0, 54, 10}, OptionalDouble.empty()).pitch(), TOLERANCE);
        assertEquals(-45, CameraShots.lookingAt(here, new double[] {0, 74, 10}, OptionalDouble.empty()).pitch(), TOLERANCE);
        assertEquals(90, CameraShots.lookingAt(here, new double[] {0, 54, 0}, OptionalDouble.empty()).pitch(), TOLERANCE);
    }

    @Test
    void orbitPutsACameraOnEachSideAtTheSameDistanceLookingBack() {
        double[] target = {100, 70, 200};
        List<Shot> shots = CameraShots.orbit(target, 20, 30, List.of("north", "east", "south", "west"), true, OptionalDouble.of(60));
        assertEquals(5, shots.size());
        for (Shot shot : shots) {
            Pose pose = pose(shot);
            double away = Math.sqrt(Math.pow(pose.x() - 100, 2) + Math.pow(pose.y() - 70, 2) + Math.pow(pose.z() - 200, 2));
            assertEquals(20, away, TOLERANCE, shot.label());
            assertEquals(60, pose.fov().orElseThrow(), TOLERANCE);
        }
        Pose north = pose(shots.get(0));
        assertTrue(north.z() < 200 && Math.abs(north.x() - 100) < TOLERANCE, "北面的机位在目标的 -z 一侧");
        assertEquals(0, north.yaw(), TOLERANCE, "从北面看，镜头朝南");
        assertEquals(30, north.pitch(), TOLERANCE, "抬高 30 度就低头 30 度看回去");
        assertEquals(80, north.y(), TOLERANCE);
        Pose east = pose(shots.get(1));
        assertTrue(east.x() > 100);
        assertEquals(90, east.yaw(), TOLERANCE, "从东面看，镜头朝西");
        assertTrue(shots.get(0).label().contains("北面") && shots.get(0).label().contains("朝南"), shots.get(0).label());
        Pose top = pose(shots.get(4));
        assertEquals(90, top.pitch(), TOLERANCE);
        assertEquals(90, top.y(), TOLERANCE);
        assertTrue(shots.get(4).label().contains("俯视"));
    }

    @Test
    void requestsAreParsedIntoShots() {
        Plan camera = CameraShots.plan(json("{\"camera\": {\"position\": [0.5, 70, 0.5], \"look_at\": [0.5, 60, 10.5], \"fov\": 50}, \"width\": 640}"));
        assertEquals(640, camera.width());
        assertEquals(45, pose(camera.shots().getFirst()).pitch(), TOLERANCE);
        assertEquals(50, pose(camera.shots().getFirst()).fov().orElseThrow(), TOLERANCE);
        Plan angles = CameraShots.plan(json("{\"camera\": {\"position\": [1, 2, 3], \"yaw\": -90}}"));
        assertEquals(-90, pose(angles.shots().getFirst()).yaw(), TOLERANCE);
        assertEquals(0, pose(angles.shots().getFirst()).pitch(), TOLERANCE);
        Plan orbit = CameraShots.plan(json("{\"orbit\": {\"target\": [10, 64, 10], \"distance\": 12, \"sides\": [\"south\", \"south\"], \"top\": true}}"));
        assertEquals(2, orbit.shots().size(), "重复写的面只拍一次，再加一张俯视");
        assertEquals(CameraShots.DEFAULT_ORBIT_WIDTH, orbit.width());
        assertEquals(10, orbit.target().orElseThrow()[0], TOLERANCE);
        assertTrue(camera.target().isEmpty());
    }

    @Test
    void wrongRequestsSayHowToWriteThem() {
        assertRejected("{\"camera\": {\"position\": [0, 0, 0], \"yaw\": 0}, \"orbit\": {\"target\": [0, 0, 0], \"distance\": 5}}", "只能给一个");
        assertRejected("{\"hud\": true, \"camera\": {\"position\": [0, 0, 0], \"yaw\": 0}}", "不能同时指定机位");
        assertRejected("{\"camera\": {\"look_at\": [0, 0, 0]}}", "position");
        assertRejected("{\"camera\": {\"position\": [0, 0, 0]}}", "look_at");
        assertRejected("{\"camera\": {\"position\": [0, 0, 0], \"look_at\": [1, 1, 1], \"yaw\": 3}}", "不要混用");
        assertRejected("{\"camera\": {\"position\": [0, 0, 0], \"yaw\": 0, \"pitch\": 120}}", "pitch");
        assertRejected("{\"camera\": {\"position\": [0, 0], \"yaw\": 0}}", "[x, y, z]");
        assertRejected("{\"camera\": {\"position\": [0, 0, 0], \"yaw\": 0, \"fov\": 150}}", "fov");
        assertRejected("{\"orbit\": {\"target\": [0, 0, 0]}}", "distance");
        assertRejected("{\"orbit\": {\"target\": [0, 0, 0], \"distance\": 0.5}}", "distance");
        assertRejected("{\"orbit\": {\"target\": [0, 0, 0], \"distance\": 5, \"sides\": [\"up\"]}}", "north、east、south、west");
        assertRejected("{\"orbit\": {\"target\": [0, 0, 0], \"distance\": 5, \"sides\": []}}", "一张都拍不了");
        assertRejected("{\"width\": 100}", "width");
    }

    private static void assertRejected(String request, String part) {
        BridgeException rejected = assertThrows(BridgeException.class, () -> CameraShots.plan(json(request)), request);
        assertEquals(400, rejected.status());
        assertTrue(rejected.getMessage().contains(part), rejected.getMessage());
    }
}
