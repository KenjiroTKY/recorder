package com.hqrecorder.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LevelMeterRow(left: Float, right: Float, isStereo: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(if (isStereo) "L" else "MONO")
        LinearProgressIndicator(
            progress = { left.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
        if (isStereo) {
            Spacer(Modifier.height(8.dp))
            Text("R")
            LinearProgressIndicator(
                progress = { right.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
        }
    }
}
