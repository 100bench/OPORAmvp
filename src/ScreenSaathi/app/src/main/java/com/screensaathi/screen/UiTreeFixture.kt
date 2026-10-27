package com.screensaathi.screen

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

data class UiTreeFixture(
    val schemaVersion: Int,
    val captureKind: String,
    val packageName: String,
    val settled: Boolean,
    val activeDialog: Boolean,
    val viewport: ScreenViewport?,
    val elements: List<UiTreeFixtureElement>,
)

/** Whitelisted, sanitized fields only. Never stores the source element. */
data class UiTreeFixtureElement(
    val index: Int,
    val resourceId: String,
    val text: String,
    val className: String,
    val bounds: ScreenViewport,
    val editable: Boolean,
    val clickable: Boolean,
    val contentDescription: String,
    val hintText: String,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
    val inputType: Int,
    val visibleToUser: Boolean,
    val inDialog: Boolean,
    val clickableAncestorIndex: Int?,
)

/** Local fixture boundary, deliberately stricter about labels than the planner projection. */
object UiTreeFixtureCodec {
    private const val VERSION = 1
    private const val KIND = "sanitized_accessibility"
    private const val REDACTED = "[REDACTED]"

    fun encodeSanitized(snapshot: ScreenSnapshot, approvedStaticLabels: Set<String>): String {
        fun label(value: String, editable: Boolean): String = when {
            editable || value.isBlank() -> ""
            value in approvedStaticLabels -> safeValue(value)
            else -> REDACTED
        }
        val retained = snapshot.elements.filterNot { it.password }
        val indices = retained.map { it.index }.toSet()
        // Finish sanitizing all strings in memory before constructing the serialized payload.
        val fixture = UiTreeFixture(VERSION, KIND, safeValue(snapshot.packageName), snapshot.settled,
            snapshot.activeDialog, snapshot.viewport, retained.map { e ->
                UiTreeFixtureElement(e.index, safeValue(e.resourceId), label(e.text, e.editable),
                    safeValue(e.className), ScreenViewport(e.bounds.left, e.bounds.top, e.bounds.right, e.bounds.bottom),
                    e.editable, e.clickable, label(e.contentDescription, e.editable), label(e.hintText, e.editable),
                    e.enabled, e.selected, e.checked, e.scrollable, e.inputType, e.visibleToUser, e.inDialog,
                    e.clickableAncestorIndex?.takeIf { it in indices })
            })
        return JSONObject().put("schemaVersion", fixture.schemaVersion).put("captureKind", fixture.captureKind)
            .put("packageName", fixture.packageName).put("settled", fixture.settled)
            .put("activeDialog", fixture.activeDialog).put("viewport", fixture.viewport?.toJson() ?: JSONObject.NULL)
            .put("elements", JSONArray().apply { fixture.elements.forEach { e ->
                put(JSONObject().put("index", e.index).put("resourceId", e.resourceId).put("text", e.text)
                    .put("className", e.className).put("bounds", e.bounds.toJson()).put("editable", e.editable)
                    .put("clickable", e.clickable).put("contentDescription", e.contentDescription).put("hintText", e.hintText)
                    .put("enabled", e.enabled).put("selected", e.selected).put("checked", e.checked)
                    .put("scrollable", e.scrollable).put("inputType", e.inputType).put("visibleToUser", e.visibleToUser)
                    .put("inDialog", e.inDialog).put("clickableAncestorIndex", e.clickableAncestorIndex ?: JSONObject.NULL))
            } }).toString(2)
    }

    /** Replay only: rejects other versions, raw/extra fields and unsanitized field values. */
    fun decode(json: String): ScreenSnapshot = try {
        val root = JSONObject(json)
        root.requireKeys(setOf("schemaVersion", "captureKind", "packageName", "settled", "activeDialog", "viewport", "elements"))
        require(root.getInt("schemaVersion") == VERSION && root.getString("captureKind") == KIND)
        val elements = root.getJSONArray("elements")
        val decoded = (0 until elements.length()).map { i ->
            val e = elements.getJSONObject(i)
            e.requireKeys(setOf("index", "resourceId", "text", "className", "bounds", "editable", "clickable",
                "contentDescription", "hintText", "enabled", "selected", "checked", "scrollable", "inputType",
                "visibleToUser", "inDialog", "clickableAncestorIndex"))
            val editable = e.getBoolean("editable")
            val text = e.safeString("text")
            val description = e.safeString("contentDescription")
            val hint = e.safeString("hintText")
            require(!editable || (text.isEmpty() && description.isEmpty() && hint.isEmpty()))
            val bounds = e.getJSONObject("bounds").viewport()
            ScreenElement(e.getInt("index"), e.safeString("resourceId"), text, e.safeString("className"),
                Rect().apply { left = bounds.left; top = bounds.top; right = bounds.right; bottom = bounds.bottom },
                editable, e.getBoolean("clickable"), description, hint, e.getBoolean("enabled"),
                e.getBoolean("selected"), e.getBoolean("checked"), e.getBoolean("scrollable"),
                password = false, inputType = e.getInt("inputType"), visibleToUser = e.getBoolean("visibleToUser"),
                inDialog = e.getBoolean("inDialog"),
                clickableAncestorIndex = if (e.isNull("clickableAncestorIndex")) null else e.getInt("clickableAncestorIndex"))
        }
        val indices = decoded.map { it.index }
        require(indices.size == indices.toSet().size && indices.all { it >= 0 })
        require(decoded.all { it.clickableAncestorIndex == null || it.clickableAncestorIndex in indices })
        ScreenSnapshot(root.safeString("packageName"), root.getBoolean("settled"), decoded,
            root.getBoolean("activeDialog"), if (root.isNull("viewport")) null else root.getJSONObject("viewport").viewport())
    } catch (_: Exception) {
        // Never include the input, JSON parser message or cause: they can contain raw data.
        throw IllegalArgumentException("Invalid sanitized UI-tree fixture")
    }

    private fun safeValue(value: String) = ScreenSanitizer.sanitizeValue(value).orEmpty()
    private fun JSONObject.safeString(key: String): String = getString(key).also { require(it == safeValue(it)) }
    private fun JSONObject.requireKeys(expected: Set<String>) { require(keys().asSequence().toSet() == expected) }
    private fun ScreenViewport.toJson() = JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom)
    private fun JSONObject.viewport(): ScreenViewport {
        requireKeys(setOf("left", "top", "right", "bottom"))
        return ScreenViewport(getInt("left"), getInt("top"), getInt("right"), getInt("bottom"))
    }
}
