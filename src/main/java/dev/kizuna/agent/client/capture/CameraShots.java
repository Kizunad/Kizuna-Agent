package dev.kizuna.agent.client.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kizuna.agent.bridge.BridgeException;
import dev.kizuna.agent.bridge.Requests;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 一次截图请求要拍哪几张、每张的机位在哪儿：只做算术，不碰游戏。
 *
 * <p>朝向用游戏里的写法：yaw 0 朝南（+z）、90 朝西（-x）、180 朝北、-90 朝东；pitch 正数是低头，90 是垂直朝下。</p>
 */
public final class CameraShots {
    /**
     * 一个机位。
     *
     * @param fov 视野角（度）；没给就用玩家设置里的
     */
    public record Pose(double x, double y, double z, float yaw, float pitch, OptionalDouble fov) {
    }

    /**
     * 一张要拍的图。
     *
     * @param label 给助手看的说明：从哪儿往哪儿看
     * @param pose 机位；为空表示就用玩家现在的视角
     * @param hud 连 HUD 和打开的界面一起截
     */
    public record Shot(String label, Optional<Pose> pose, boolean hud) {
    }

    /**
     * 一次请求。
     *
     * @param width 图片最宽多少像素
     * @param target 绕着看时围着的那个点；别的拍法为空
     */
    public record Plan(List<Shot> shots, int width, Optional<double[]> target) {
    }

    public static final int MIN_WIDTH = 320;
    public static final int MAX_WIDTH = 1920;
    static final int DEFAULT_WIDTH = 960;
    /** 绕着看一次出好几张，每张默认小一些。 */
    static final int DEFAULT_ORBIT_WIDTH = 768;
    static final double MIN_FOV = 30;
    static final double MAX_FOV = 110;
    static final double DEFAULT_ELEVATION = 25;
    static final double MAX_DISTANCE = 256;
    private static final List<String> SIDES = List.of("north", "east", "south", "west");
    private static final float STRAIGHT_DOWN = 90;
    private static final float FACING_NORTH = 180;

    private CameraShots() {
        // 只有静态算法。
    }

    /** 解析请求体。写得不对的抛 {@link BridgeException}，话里说清该怎么写。 */
    public static Plan plan(JsonObject request) {
        boolean hud = Requests.flag(request, "hud", false);
        Optional<JsonObject> camera = Requests.optionalObject(request, "camera");
        Optional<JsonObject> orbit = Requests.optionalObject(request, "orbit");
        if (camera.isPresent() && orbit.isPresent()) {
            throw BridgeException.badRequest("camera 和 orbit 只能给一个：camera 是单个机位，orbit 是绕着一个点拍几面");
        }
        if (hud && (camera.isPresent() || orbit.isPresent())) {
            throw BridgeException.badRequest("hud 为 true 是截玩家眼前的画面（含 HUD 和打开的界面），不能同时指定机位");
        }
        int fallback = orbit.isPresent() ? DEFAULT_ORBIT_WIDTH : DEFAULT_WIDTH;
        int width = Requests.integer(request, "width", fallback);
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            throw BridgeException.badRequest("width 要在 " + MIN_WIDTH + " 到 " + MAX_WIDTH + " 之间，收到 " + width);
        }
        if (orbit.isPresent()) {
            return new Plan(orbit(orbit.get()), width, Requests.optionalPoint(orbit.get(), "target"));
        }
        if (camera.isPresent()) {
            return new Plan(List.of(camera(camera.get())), width, Optional.empty());
        }
        return new Plan(List.of(new Shot(hud ? "玩家眼前的画面（含 HUD 和打开的界面）" : "玩家现在的视角（不含 HUD）",
                Optional.empty(), hud)), width, Optional.empty());
    }

    private static Shot camera(JsonObject camera) {
        double[] position = Requests.optionalPoint(camera, "position")
                .orElseThrow(() -> BridgeException.badRequest("camera 里要有 position（[x, y, z]，机位在哪儿）"));
        OptionalDouble fov = fov(camera);
        Optional<double[]> lookAt = Requests.optionalPoint(camera, "look_at");
        OptionalDouble yaw = Requests.optionalNumber(camera, "yaw");
        OptionalDouble pitch = Requests.optionalNumber(camera, "pitch");
        if (lookAt.isPresent() && (yaw.isPresent() || pitch.isPresent())) {
            throw BridgeException.badRequest("camera 的朝向给 look_at（看向哪个点），或者给 yaw、pitch，两种不要混用");
        }
        if (lookAt.isPresent()) {
            Pose pose = lookingAt(position, lookAt.get(), fov);
            return new Shot("从 " + point(position) + " 看向 " + point(lookAt.get()), Optional.of(pose), false);
        }
        if (yaw.isEmpty()) {
            throw BridgeException.badRequest("camera 里要有 look_at（看向哪个点，[x, y, z]），或者 yaw（可再加 pitch）");
        }
        float tilt = (float) pitch.orElse(0);
        if (Math.abs(tilt) > STRAIGHT_DOWN) {
            throw BridgeException.badRequest("pitch 要在 -90（抬头看天）到 90（低头看地）之间，收到 " + tilt);
        }
        var pose = new Pose(position[0], position[1], position[2], (float) yaw.getAsDouble(), tilt, fov);
        return new Shot("机位 " + point(position) + "，yaw " + trim(pose.yaw()) + "，pitch " + trim(pose.pitch()), Optional.of(pose), false);
    }

    private static List<Shot> orbit(JsonObject orbit) {
        double[] target = Requests.optionalPoint(orbit, "target")
                .orElseThrow(() -> BridgeException.badRequest("orbit 里要有 target（[x, y, z]，围着哪个点看；一般取建筑的中心）"));
        double distance = Requests.optionalNumber(orbit, "distance")
                .orElseThrow(() -> BridgeException.badRequest("orbit 里要有 distance（机位离 target 多少格；要比建筑的半宽大，能把它装进画面）"));
        if (distance < 1 || distance > MAX_DISTANCE) {
            throw BridgeException.badRequest("distance 要在 1 到 " + (int) MAX_DISTANCE + " 之间，收到 " + distance);
        }
        double elevation = Requests.optionalNumber(orbit, "elevation").orElse(DEFAULT_ELEVATION);
        if (elevation < -80 || elevation > 80) {
            throw BridgeException.badRequest("elevation（机位抬高的角度）要在 -80 到 80 之间，收到 " + elevation + "；要正上方往下看用 top: true");
        }
        var sides = new ArrayList<String>();
        if (orbit.has("sides") && !orbit.get("sides").isJsonNull()) {
            for (JsonElement side : Requests.array(orbit, "sides")) {
                String name = side.isJsonPrimitive() ? side.getAsString() : side.toString();
                if (!SIDES.contains(name)) {
                    throw BridgeException.badRequest("sides 里只能写 north、east、south、west（机位在目标的哪一面），收到 " + name);
                }
                if (!sides.contains(name)) {
                    sides.add(name);
                }
            }
        } else {
            sides.addAll(SIDES);
        }
        boolean top = Requests.flag(orbit, "top", false);
        if (sides.isEmpty() && !top) {
            throw BridgeException.badRequest("sides 是空的又没要 top，一张都拍不了");
        }
        return orbit(target, distance, elevation, sides, top, fov(orbit));
    }

    /**
     * 围着 {@code target} 的几个机位，都看向它。
     *
     * @param elevation 机位比目标抬高的角度（度）
     * @param sides 机位在目标的哪几面：north、east、south、west
     * @param top 再加一张正上方往下看的
     */
    static List<Shot> orbit(double[] target, double distance, double elevation, List<String> sides, boolean top, OptionalDouble fov) {
        double flat = distance * Math.cos(Math.toRadians(elevation));
        double height = distance * Math.sin(Math.toRadians(elevation));
        var shots = new ArrayList<Shot>();
        for (String side : sides) {
            double[] offset = switch (side) {
                case "north" -> new double[] {0, -flat};
                case "south" -> new double[] {0, flat};
                case "east" -> new double[] {flat, 0};
                case "west" -> new double[] {-flat, 0};
                default -> throw new IllegalArgumentException(side);
            };
            double[] position = {target[0] + offset[0], target[1] + height, target[2] + offset[1]};
            shots.add(new Shot("从" + name(side) + "面看（镜头朝" + name(opposite(side)) + "）", Optional.of(lookingAt(position, target, fov)), false));
        }
        if (top) {
            shots.add(new Shot("正上方俯视（图的上边是北，右边是东）",
                    Optional.of(new Pose(target[0], target[1] + distance, target[2], FACING_NORTH, STRAIGHT_DOWN, fov)), false));
        }
        return List.copyOf(shots);
    }

    /** 站在 {@code from} 看向 {@code target} 的机位。两点重合时朝南平视。 */
    static Pose lookingAt(double[] from, double[] target, OptionalDouble fov) {
        double dx = target[0] - from[0];
        double dy = target[1] - from[1];
        double dz = target[2] - from[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = flat < 1e-6 ? 0 : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = flat < 1e-6 && Math.abs(dy) < 1e-6 ? 0 : (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new Pose(from[0], from[1], from[2], yaw, pitch, fov);
    }

    private static OptionalDouble fov(JsonObject source) {
        OptionalDouble fov = Requests.optionalNumber(source, "fov");
        if (fov.isPresent() && (fov.getAsDouble() < MIN_FOV || fov.getAsDouble() > MAX_FOV)) {
            throw BridgeException.badRequest("fov（视野角）要在 " + (int) MIN_FOV + " 到 " + (int) MAX_FOV + " 之间，收到 " + fov.getAsDouble());
        }
        return fov;
    }

    private static String name(String side) {
        return switch (side) {
            case "north" -> "北";
            case "south" -> "南";
            case "east" -> "东";
            default -> "西";
        };
    }

    private static String opposite(String side) {
        return switch (side) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    private static String point(double[] point) {
        return "[" + trim(point[0]) + ", " + trim(point[1]) + ", " + trim(point[2]) + "]";
    }

    private static String trim(double value) {
        // 整数不带小数点，别的留一位。
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
