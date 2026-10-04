package com.shuffleframe.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun ShuffleApp(vm: SlideshowViewModel = viewModel()) {
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onMediaPermissionResult() }
    val mediaPermissions = remember { MediaAccess.permissionsToRequest() }

    val pickFolder: () -> Unit = { folderPicker.launch(null) }
    val askForPhotos: () -> Unit = { permissionRequest.launch(mediaPermissions) }
    val pickAlbum: () -> Unit = {
        if (MediaAccess.has(context)) vm.openAlbumPicker() else askForPhotos()
    }
    val openAppSettings: () -> Unit = {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    BackHandler(enabled = vm.canHandleBack) { vm.back() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Crossfade(targetState = vm.uiState, animationSpec = tween(600), label = "screen") { state ->
            when (state) {
                UiState.Loading -> LoadingScreen()

                is UiState.ChooseSource -> MessageScreen(
                    title = "Shuffle",
                    subtitle = "Your photos, in no particular order.",
                    hero = true,
                    primary = Action("Choose album", AppIcons.Photos, pickAlbum),
                    secondary = Action("or pick a folder", null, pickFolder),
                    back = if (state.canGoBack) vm::back else null,
                )

                is UiState.AlbumPicker -> AlbumPickerScreen(
                    state = state,
                    onPick = vm::onAlbumPicked,
                    onFolder = pickFolder,
                    onChangeSelection = askForPhotos,
                    onBack = vm::back,
                )

                is UiState.MediaDenied -> MessageScreen(
                    title = "No access to photos",
                    subtitle = "Shuffle needs permission to see your photos. Allow it in Settings under Permissions → Photos and videos.",
                    primary = Action("Open Settings", null, openAppSettings),
                    secondary = Action("or pick a folder instead", null, pickFolder),
                    back = vm::back,
                )

                is UiState.Empty -> MessageScreen(
                    title = "Nothing to shuffle",
                    subtitle = if (state.isFolder)
                        "No photos directly inside “${state.name}”. If your photos are in a folder within it, pick that one, or use albums instead."
                    else
                        "“${state.name}” has no photos stored on this phone.",
                    primary = Action("Choose album", AppIcons.Photos, pickAlbum),
                    secondary = Action("or pick a folder", null, pickFolder),
                    back = if (state.canGoBack) vm::back else null,
                )

                is UiState.Failed -> MessageScreen(
                    title = "Couldn’t read “${state.name}”",
                    subtitle = "Android may have withdrawn access. Choose it again to continue.",
                    primary = Action("Choose album", AppIcons.Photos, pickAlbum),
                    secondary = Action("or pick a folder", null, pickFolder),
                    back = if (state.canGoBack) vm::back else null,
                )

                is UiState.Ready -> SlideshowScreen(
                    vm = vm,
                    folderName = state.name,
                    count = state.count,
                    onChangeSource = vm::chooseSource,
                )
            }
        }
    }
}

private data class Action(val label: String, val icon: ImageVector?, val onClick: () -> Unit)

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Amber, strokeWidth = 1.5.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(18.dp))
        Text("Gathering photos…", color = Muted, fontSize = 14.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun MessageScreen(
    title: String,
    subtitle: String,
    primary: Action,
    secondary: Action? = null,
    back: (() -> Unit)? = null,
    hero: Boolean = false,
) {
    Box(Modifier.fillMaxSize()) {
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
                    .clickable(onClick = primary.onClick)
                    .padding(horizontal = 28.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (primary.icon != null) {
                    Icon(primary.icon, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(primary.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (secondary != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    secondary.label,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = secondary.onClick)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        if (back != null) {
            Text(
                "Back to slideshow",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = back)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AlbumPickerScreen(
    state: UiState.AlbumPicker,
    onPick: (Album) -> Unit,
    onFolder: () -> Unit,
    onChangeSelection: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 28.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Albums", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
                Text("Pick one to shuffle", color = Muted, fontSize = 14.sp)
            }
            Text(
                if (state.canGoBack) "Cancel" else "Back",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(12.dp),
            )
        }

        if (state.limited) {
            Text(
                "You’ve given access to selected photos only. Tap to change which photos Shuffle can see.",
                color = Amber,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Amber.copy(alpha = 0.1f))
                    .clickable(onClick = onChangeSelection)
                    .padding(14.dp),
            )
        }

        if (state.albums.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(36.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No photos found on this phone", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Photos that only live in the cloud don’t count until they’re downloaded.",
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                FolderLink(onFolder)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.albums, key = { it.id ?: "__all__" }) { album ->
                    AlbumCard(album) { onPick(album) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        FolderLink(onFolder)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderLink(onFolder: () -> Unit) {
    Text(
        "Use a folder instead",
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 15.sp,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onFolder).padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(album.cover) {
        ImageRequest.Builder(context).data(album.cover).size(400).build()
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Panel),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            album.name,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            "${album.count} photo${if (album.count == 1) "" else "s"}",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
