package com.screensaathi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.screensaathi.opora.OporaSettings

/**
 * Setup gateway only. Grants the three permissions, lets the user open the demo
 * screen, and starts the overlay. Not part of the demo flow itself — once the
 * pill is up, the user never comes back here.
 */
class MainActivity : AppCompatActivity() {

    private val micRequest = 101
    private val callRequest = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }

        findViewById<Button>(R.id.btn_mic).setOnClickListener {
            if (!hasMic()) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), micRequest
                )
            }
        }

        findViewById<Button>(R.id.btn_call).setOnClickListener {
            if (!hasCallPermission()) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CALL_PHONE), callRequest
                )
            }
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            explainAccessibilityThenOpenSettings()
        }

        val saved = OporaSettings.load(this)
        findViewById<EditText>(R.id.contact_name).setText(saved.contactName)
        findViewById<EditText>(R.id.contact_phone).setText(saved.contactPhone)

        findViewById<Button>(R.id.btn_save_targets).setOnClickListener { saveContact() }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("Сначала разрешите показ поверх других приложений.")
                return@setOnClickListener
            }
            OverlayService.start(this)
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /**
     * `BIND_ACCESSIBILITY_SERVICE` is the single scariest permission dialog on
     * Android, and the OS prompt explains none of it — the audit that flagged
     * this called it out as the single biggest reason a first-time user quits
     * before ever seeing the product work. This dialog is the one screen
     * between "unexplained scary permission" and an informed decision; the
     * full accounting lives in docs/DATA_HANDLING.md if that's not enough.
     */
    private fun explainAccessibilityThenOpenSettings() {
        AlertDialog.Builder(this)
            .setTitle("Зачем нужен доступ к экрану")
            .setMessage(
                "Приложение сможет читать текст и структуру текущего экрана, " +
                    "чтобы подсвечивать нужные поля и кнопки.\n\n" +
                    "Оно читает только открытое приложение в виде текста: " +
                    "не просматривает фоновые приложения; снимок экрана требует " +
                    "отдельного действия пользователя. " +
                    "Текст отправляется AI-сервису для выбора следующей подсказки.\n\n" +
                    "На следующем экране найдите «Опору» в списке установленных " +
                    "служб и включите его."
            )
            .setPositiveButton("Продолжить") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Не сейчас", null)
            .show()
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        val mic = if (hasMic()) "✓" else "✗"
        val acc = if (isAccessibilityEnabled()) "✓" else "✗"
        val call = if (hasCallPermission()) "✓" else "✗"
        findViewById<TextView>(R.id.setup_status).text =
            "Поверх окон $overlay   Микрофон $mic\nЧтение экрана $acc   Звонки $call"
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    private fun saveContact() {
        val contactName = findViewById<EditText>(R.id.contact_name).text.toString().trim()
        val rawPhone = findViewById<EditText>(R.id.contact_phone).text.toString()
        val phone = OporaSettings.normalizedPhone(rawPhone)
        when {
            contactName.isBlank() -> toast("Введите имя контакта.")
            phone == null -> toast("Введите обычный номер из 7–15 цифр. USSD и экстренные номера запрещены.")
            else -> {
                OporaSettings.save(
                    this,
                    OporaSettings(contactName, phone),
                )
                findViewById<EditText>(R.id.contact_phone).setText(phone)
                toast("Контакт сохранён только на этом телефоне.")
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/$packageName.ScreenReaderService"
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }
}
