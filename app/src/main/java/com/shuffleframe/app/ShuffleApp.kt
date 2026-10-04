package com.shuffleframe.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ShuffleApp(vm: SlideshowViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }
    val pickFolder: () -> Unit = { picker.launch(null) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Crossfade(targetState = vm.uiState, animationSpec = tween(600), label = "screen") { state ->
            when (state) {
                UiState.Loading -> LoadingScreen()
                UiState.NeedFolder -> MessageScreen(
                    title = "Shuffle",
                    subtitle = "Your photos, in no particular order.",
                    buttonLabel = "Choose folder",
                    footnote = "Any folder works except Downloads or the top level of your storage. Android doesn't allow those.",
                    hero = true,
                    onClick = pickFolder,
                )
                is UiState.Empty -> MessageScreen(
                    title = "Nothing to shuffle",
                    subtitle = "No JPEG or PNG photos found directly inside “${state.folderName}”.",
                    buttonLabel = "Choose another folder",
                    onClick = pickFolder,
                )
                is UiState.Failed -> MessageScreen(
                    title = "Couldn’t read that folder",
                    subtitle = "Android may have withdrawn access to “${state.folderName}”. Choose it again to continue.",
                    buttonLabel = "Choose folder",
                    onClick = pickFolder,
                )
                is UiState.Ready -> SlideshowScreen(
                    vm = vm,
                    folderName = state.folderName,
                    count = state.count,
                    onChangeFolder = pickFolder,
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Amber, strokeWidth = 1.5.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(18.dp))
        Text("Reading folder…", color = Muted, fontSize = 14.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun MessageScreen(
    title: String,
    subtitle: String,
    buttonLabel: String,
    onClick: () -> Unit,
    footnote: String? = null,
    hero: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = if (hero) 56.sp else 30.sp,
            fontWeight = if (hero) FontWeight.Thin else FontWeight.Light,
            letterSpacing = if (hero) 4.sp else 0.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(subtitle, color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier
                .clip(CircleShape)
                .background(Amber)
                .clickable(onClick = onClick)
                .padding(horizontal = 28.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Folder, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(buttonLabel, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        if (footnote != null) {
            Spacer(Modifier.height(28.dp))
            Text(footnote, color = Muted.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
        }
    }
}
