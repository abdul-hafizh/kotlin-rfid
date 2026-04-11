package com.example.newrfid

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "RFID Scanner"

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.isUserInputEnabled = false
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = TabAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> tab.text = "Scan"
                1 -> tab.text = "Regis Tag"
                2 -> tab.text = "Unregis Tag"
            }
        }.attach()
    }
}