package com.example.ava.ui.screens.settings.components

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import java.net.NetworkInterface
import com.example.ava.ui.theme.SlateText as SlateTextLight
import com.example.ava.ui.theme.SlateBorder as CardBorder

private val SlateTextDark = Color(0xFFF1F5F9)
private val CardBackgroundLight = Color.White
private val CardBackgroundDark = Color(0xFF1F1F1F)

@Composable
fun AboutSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val slateText = if (isDarkMode) SlateTextDark else SlateTextLight
    val cardBackground = if (isDarkMode) CardBackgroundDark else CardBackgroundLight
    
    val versionName = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            "v${packageInfo.versionName}"
        } catch (e: Exception) {
            "v0.0.0"
        }
    }
    
    val deviceIp = remember {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList()
            interfaces?.find { it.displayName.contains("wlan") || it.displayName.contains("eth") }
                ?.inetAddresses?.toList()
                ?.find { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        
        Surface(
            shape = RoundedCornerShape(50),
            color = cardBackground,
            shadowElevation = if (isDarkMode) 0.dp else 1.dp,
            modifier = Modifier.clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/knoop7/Ava"))
                context.startActivity(intent)
            }
        ) {
            Row(
                modifier = Modifier
                    .border(1.dp, if (isDarkMode) Color(0xFF2D2D2D) else CardBorder, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.github_24px),
                    contentDescription = "GitHub",
                    tint = slateText,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.github_project),
                    color = slateText,
                    fontSize = settingsBodyTextSize(),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        
        Text(
            text = stringResource(R.string.ava_version, versionName),
            color = getSettingsDescriptionColor(),
            fontSize = settingsBodyTextSize(),
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = stringResource(R.string.device_ip, deviceIp),
            color = getSettingsDescriptionColor(),
            fontSize = 11.sp
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        
        Text(
            text = stringResource(R.string.original_author),
            color = getSettingsDescriptionColor(),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.secondary_dev),
            color = getSettingsDescriptionColor(),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}
