package com.anshin.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** 跨端契约回归：远程清单字段、ISO 时间解析、批量上限。 */
public class RemoteDtoTest {

    private static JSONObject scanned(String name, String category) throws org.json.JSONException {
        JSONObject item = new JSONObject();
        item.put("packageName", name);
        item.put("label", "测试应用");
        item.put("category", category);
        item.put("removable", true);
        item.put("system", false);
        item.put("versionName", "1.0");
        item.put("reason", "本地解释字段，不允许上传");
        return item;
    }

    @Test
    public void inventoryItemStripsLocalFieldsAndKeepsServerShape() throws Exception {
        JSONObject item = RemoteDto.inventoryItem(scanned("com.example.app", "user"));
        assertEquals(6, item.length());
        assertTrue(item.has("packageName") && item.has("label") && item.has("category")
                && item.has("removable") && item.has("system") && item.has("versionName"));
        assertFalse(item.has("reason"));
    }

    @Test
    public void inventoryItemFallsBackToPackageNameWhenLabelEmpty() throws Exception {
        JSONObject scanned = scanned("com.example.app", "user");
        scanned.put("label", "");
        assertEquals("com.example.app", RemoteDto.inventoryItem(scanned).getString("label"));
        scanned.put("label", "\u0007\u001f");
        assertEquals("com.example.app", RemoteDto.inventoryItem(scanned).getString("label"));
    }

    @Test
    public void inventoryItemSanitizesControlCharsAndTruncates() {
        assertEquals("ab", RemoteDto.sanitizeText("a\u0007b\n", 200));
        assertEquals(200, RemoteDto.sanitizeText("x".repeat(500), 200).length());
        assertNull(RemoteDto.sanitizeText(null, 200));
        assertNull(RemoteDto.sanitizeText("  \u0000 ", 200));
    }

    @Test
    public void inventoryItemRejectsInvalidNameAndCategory() {
        assertThrows(IllegalArgumentException.class, () -> RemoteDto.inventoryItem(scanned("bad name", "user")));
        assertThrows(IllegalArgumentException.class, () -> RemoteDto.inventoryItem(scanned("com.example.app", "virus")));
        assertThrows(IllegalArgumentException.class, () -> RemoteDto.inventoryItem(null));
    }

    @Test
    public void inventoryRejectsDuplicatesAndOversizedLists() throws org.json.JSONException {
        JSONArray duplicate = new JSONArray()
                .put(scanned("com.example.app", "user"))
                .put(scanned("com.example.app", "core"));
        assertThrows(IllegalArgumentException.class, () -> RemoteDto.inventory(duplicate));
        JSONArray oversized = new JSONArray();
        for (int i = 0; i < 1001; i++) oversized.put(scanned("com.example.a" + i, "user"));
        assertThrows(IllegalArgumentException.class, () -> RemoteDto.inventory(oversized));
    }

    @Test
    public void actualAndroidAndHuaweiFrameworksCanBeListedButNeverRemoved() throws Exception {
        JSONArray input=new JSONArray();
        for(String name:new String[]{"android","androidhwext"}){
            JSONObject item=scanned(name,"core").put("system",true).put("removable",false);
            input.put(item);
            assertFalse(PackagePolicy.validName(name));
            assertTrue(PackagePolicy.validInventoryName(name));
        }
        JSONArray result=RemoteDto.inventory(input);
        assertEquals(2,result.length());
        assertEquals("androidhwext",result.getJSONObject(1).getString("packageName"));
        assertFalse(result.getJSONObject(0).getBoolean("removable"));
        assertThrows(IllegalArgumentException.class,()->RemoteDto.inventoryItem(scanned("android","user")));
        assertThrows(IllegalArgumentException.class,()->RemoteDto.inventoryItem(scanned("framework","core")));
    }

    @Test
    public void inventoryMapsWholeList() throws Exception {
        JSONArray out = RemoteDto.inventory(new JSONArray().put(scanned("com.example.app", "user")));
        assertEquals(1, out.length());
        assertFalse(out.getJSONObject(0).has("reason"));
    }

    @Test
    public void parseExpiresAtAcceptsIsoStringNumberAndRejectsGarbage() {
        long expected = 1_800_000_000_000L; // 2027-01-15T00:00:00Z 附近的固定值
        String iso = java.time.Instant.ofEpochMilli(expected).toString();
        assertEquals(expected, RemoteDto.parseExpiresAt(iso));
        assertEquals(expected, RemoteDto.parseExpiresAt(Long.valueOf(expected)));
        assertEquals(0L, RemoteDto.parseExpiresAt("not-a-time"));
        assertEquals(0L, RemoteDto.parseExpiresAt(""));
        assertEquals(0L, RemoteDto.parseExpiresAt(null));
        assertEquals(0L, RemoteDto.parseExpiresAt(0));
        assertEquals(0L, RemoteDto.parseExpiresAt(-5L));
    }

    @Test
    public void serverIsoFormatIsParseable() {
        // Node 的 toISOString() 形如 2026-10-04T10:00:00.000Z，必须能被 Instant.parse 解析。
        assertEquals(1_790_000_000_000L,
                RemoteDto.parseExpiresAt(java.time.Instant.ofEpochMilli(1_790_000_000_000L).toString()));
        assertTrue(RemoteDto.MAX_PACKAGES_PER_COMMAND == 50);
    }
}
