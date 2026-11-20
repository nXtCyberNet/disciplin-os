package com.personal.disciplinelock.ui.whitelist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.disciplinelock.domain.whitelist.WhitelistAppItem

enum class WhitelistFilter {
    ALL,
    WHITELISTED_ONLY,
    USER_APPS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsWhitelistScreen(
    apps: List<WhitelistAppItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onToggleWhitelist: (packageName: String) -> Unit,
    onLaunchApp: (packageName: String) -> Unit,
    onRefreshApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(WhitelistFilter.ALL) }

    val filteredApps = remember(apps, searchQuery, selectedFilter) {
        apps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                    app.name.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                WhitelistFilter.ALL -> true
                WhitelistFilter.WHITELISTED_ONLY -> app.isWhitelisted
                WhitelistFilter.USER_APPS -> !app.isSystemApp
            }

            matchesSearch && matchesFilter
        }
    }

    val whitelistedCount = remember(apps) { apps.count { it.isWhitelisted } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Permitted Apps Whitelist",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0x333FB950)
                        ) {
                            Text(
                                "$whitelistedCount Allowed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF3FB950),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshApps) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh installed apps",
                            tint = Color.LightGray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
            )
        },
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Explanatory Banner Card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF58A6FF),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Whitelisted apps remain permitted and accessible even while Disciplined Phone mode is active.",
                        fontSize = 12.sp,
                        color = Color(0xFF8B949E),
                        lineHeight = 16.sp
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps or package...", color = Color.Gray, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF58A6FF),
                    unfocusedBorderColor = Color(0xFF30363D),
                    focusedContainerColor = Color(0xFF161B22),
                    unfocusedContainerColor = Color(0xFF161B22),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == WhitelistFilter.ALL,
                    onClick = { selectedFilter = WhitelistFilter.ALL },
                    label = { Text("All (${apps.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF238636),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF161B22),
                        labelColor = Color.Gray
                    )
                )

                FilterChip(
                    selected = selectedFilter == WhitelistFilter.WHITELISTED_ONLY,
                    onClick = { selectedFilter = WhitelistFilter.WHITELISTED_ONLY },
                    label = { Text("Allowed ($whitelistedCount)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF238636),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF161B22),
                        labelColor = Color.Gray
                    )
                )

                FilterChip(
                    selected = selectedFilter == WhitelistFilter.USER_APPS,
                    onClick = { selectedFilter = WhitelistFilter.USER_APPS },
                    label = { Text("User Apps", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF238636),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF161B22),
                        labelColor = Color.Gray
                    )
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF58A6FF))
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isNotBlank()) "No apps found matching \"$searchQuery\"" else "No apps found.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppWhitelistCard(
                            app = app,
                            onToggle = { onToggleWhitelist(app.packageName) },
                            onLaunch = { onLaunchApp(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppWhitelistCard(
    app: WhitelistAppItem,
    onToggle: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isWhitelisted) Color(0xFF1A222D) else Color(0xFF161B22)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF21262D)),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.name,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        tint = if (app.isWhitelisted) Color(0xFF3FB950) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // App Label and Package
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app.isSystemApp) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0x228B949E)
                        ) {
                            Text(
                                "SYS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8B949E),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    app.packageName,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Actions: Quick Launch (if allowed) + Add/Close Toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.isWhitelisted) {
                    IconButton(
                        onClick = onLaunch,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Launch,
                            contentDescription = "Launch app",
                            tint = Color(0xFF58A6FF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Switch: ON = Added (Allowed), OFF = Closed (Removed)
                Switch(
                    checked = app.isWhitelisted,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF238636),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF30363D)
                    )
                )
            }
        }
    }
}
