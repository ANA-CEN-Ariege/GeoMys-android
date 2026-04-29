package com.example.birdstrace.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.birdstrace.R
import com.example.birdstrace.location.LocationForegroundService
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        com.example.birdstrace.store.MapTileCache.configurer(this)
        createNotificationChannel()
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            LocationForegroundService.CHANNEL_ID,
            "Suivi GPS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Suivi GPS en arrière-plan pendant une sortie"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}