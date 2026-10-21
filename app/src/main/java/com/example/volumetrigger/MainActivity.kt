package com.example.volumetrigger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 48)
        }

        val openSettingsBtn = Button(this).apply {
            text = "1. Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val resetPositionsBtn = Button(this).apply {
            text = "2. Reset Saved Coordinates"
            setOnClickListener {
                getSharedPreferences("VolumeTriggerPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
                Toast.makeText(
                    this@MainActivity,
                    "Coordinates reset! Toggle service off/on to apply.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(statusText)
            addView(openSettingsBtn)
            addView(resetPositionsBtn)
        }

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val isServiceEnabled = enabledServices.contains(packageName)

        statusText.text = if (isServiceEnabled) {
            "Status: Service is ACTIVE\n\nLaunch your game, drag VOL+ and VOL- over your buttons, then tap OFF -> LOCKED."
        } else {
            "Status: Service is NOT enabled yet.\n\nTap button #1 below and enable 'Volume Game Triggers'."
        }
    }
}
