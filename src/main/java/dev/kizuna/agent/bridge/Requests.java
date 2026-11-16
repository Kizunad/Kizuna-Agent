package dev.kizuna.agent.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.OptionalDouble;

/** 从请求体里取字段：缺了、类型不对都报成一句说得清是哪个字段的话。 */
public final class Requests {
    private Requests() {
        // 只有静态取值方法。
    }

    public static String text(JsonObject request, String name) {
        return optionalText(request, name).orElseThrow(() -> BridgeException.badRequest("缺少字段 " + name));
    }

    public static Optional<String> optionalText(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw BridgeException.badRequest("字段 " + name + " 要是字符串");
        }
        return Optional.of(value.getAsString());
    }

    public static int integer(JsonObject request, String name, int fallback) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        return integer(value, name);
    }

    public static boolean flag(JsonObject request, String name, boolean fallback) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw BridgeException.badRequest("字段 " + name + " 要是 true 或 false");
        }
        return value.getAsBoolean();
    }

    static BlockPos position(JsonObject request, String name) {
        return optionalPosition(request, name).orElseThrow(() -> BridgeException.badRequest("缺少字段 " + name + "（[x, y, z]）"));
    }

    static Optional<BlockPos> optionalPosition(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw BridgeException.badRequest("字段 " + name + " 要是 [x, y, z] 三个整数");
        }
        JsonArray array = value.getAsJsonArray();
        return Optional.of(new BlockPos(integer(array.get(0), name), integer(array.get(1), name), integer(array.get(2), name)));
    }

    /** 一个小数字段；没写为空。 */
    public static OptionalDouble optionalNumber(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(number(value, name));
    }

    /** 一个 [x, y, z] 的点，可以带小数；没写为空。 */
    public static Optional<double[]> optionalPoint(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw BridgeException.badRequest("字段 " + name + " 要是 [x, y, z] 三个数");
        }
        JsonArray array = value.getAsJsonArray();
        return Optional.of(new double[] {number(array.get(0), name), number(array.get(1), name), number(array.get(2), name)});
    }

    /** 一个子对象；没写为空。 */
    public static Optional<JsonObject> optionalObject(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonObject()) {
            throw BridgeException.badRequest("字段 " + name + " 要是一个对象 {…}");
        }
        return Optional.of(value.getAsJsonObject());
    }

    private static double number(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) {
            throw BridgeException.badRequest("字段 " + name + " 里要是数字，收到 " + value);
        }
        return value.getAsDouble();
    }

    public static JsonArray array(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || !value.isJsonArray()) {
            throw BridgeException.badRequest("字段 " + name + " 要是数组");
        }
        return value.getAsJsonArray();
    }

    static int integer(JsonElement value, String name) {
        // 坐标和下标都只收整数；1.5、"3" 这类一律拒，免得悄悄取整放错位置。
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw BridgeException.badRequest("字段 " + name + " 里要是整数");
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
            throw BridgeException.badRequest("字段 " + name + " 里要是整数，收到 " + value);
        }
        return (int) number;
    }

    static JsonArray position(BlockPos position) {
        var array = new JsonArray();
        array.add(position.getX());
        array.add(position.getY());
        array.add(position.getZ());
        return array;
    }
}
