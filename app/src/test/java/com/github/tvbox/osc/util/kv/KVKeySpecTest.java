package com.github.tvbox.osc.util.kv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 生产类型登记表的**结构**测试(不碰 Android:不实例化 MMKV、不读 SharedPreferences)。
 *
 * <p>存在理由:`KVKeySpec` 的集合类型靠匿名 `TypeToken` 子类的泛型签名恢复
 * (`TypeToken.getSuperclassTypeParameter` 读的是 class 的 Signature 属性)。R8 只保留了
 * `-keepattributes Signature`,而 `TypeToken` 子类那条规则带 `allowoptimization` ——
 * 所以"release 下泛型签名是否还在"必须**在 R8 后的字节码上**验证,不能只看 debug。
 *
 * <p>跑法:`gradlew :app:testReleaseUnitTest`(走 R8 产物);debug 跑同名测试则是不混淆基线。
 */
public class KVKeySpecTest {

    private final KVKeySpec spec = new KVKeySpec();

    @Test
    public void registeredListKey_keepsElementTypeSignature() {
        Type listType = spec.typeOf("subscribe_list");
        assertNotNull("订阅列表必须登记类型", listType);
        assertEquals(ArrayList.class, TypeToken.get(listType).getRawType());
        // 关键:元素类型是 String 而不是被擦成 Object
        assertTrue("泛型签名丢失:实际 " + listType,
                listType.toString().contains("java.lang.String"));
    }

    @Test
    public void registeredNestedMapKey_keepsBothTypeArguments() {
        Type nested = spec.typeOf("checked_sources_for_search");
        assertNotNull("嵌套泛型键必须登记类型", nested);
        assertEquals(HashMap.class, TypeToken.get(nested).getRawType());
        String text = nested.toString();
        // 嵌套泛型必须两层都在,否则内层会解成 LinkedTreeMap
        assertTrue("嵌套泛型签名丢失: " + text, text.contains("HashMap<java.lang.String, java.util.HashMap<java.lang.String, java.lang.String>>"));
    }

    @Test
    public void jsonArrayKey_isRegisteredAsJsonArrayNotList() {
        Type jsonArrayType = spec.typeOf("live_group_list");
        assertNotNull("直播分组必须登记类型", jsonArrayType);
        assertEquals(JsonArray.class, TypeToken.get(jsonArrayType).getRawType());
    }

    @Test
    public void primitiveKeys_resolveToTheirBoxedTypes() {
        assertEquals(TypeToken.get(Integer.class).getType(), spec.typeOf("play_type"));
        assertEquals(TypeToken.get(Boolean.class).getType(), spec.typeOf("incognito"));
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("api_url"));
    }

    @Test
    public void dynamicKeyFamilies_resolveByPrefix() {
        // live_group_index_<直播源地址> → int
        assertEquals(TypeToken.get(Integer.class).getType(), spec.typeOf("live_group_index_http://example.com/tv"));
        // jsRuntime_* → String
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("jsRuntime_spiderA_cookie"));
        // cache_* → String(HTTP 缓存接口,当前无调用方但登记保留)
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("cache_rule_key"));
    }

    @Test
    public void newlyAddedSettingsKey_isRegistered() {
        // 禁用手势控制(2026-09-13 新增)必须登记,否则读取要退回"调用侧默认值兜底"
        Map<String, Type> unusedGuard = new HashMap<>();
        assertTrue(unusedGuard.isEmpty());
        assertEquals(TypeToken.get(Boolean.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.GESTURE_CONTROL_DISABLED));
    }

    @Test
    public void unknownKey_returnsNull() {
        org.junit.Assert.assertNull(spec.typeOf("definitely_not_a_registered_key"));
    }
}
