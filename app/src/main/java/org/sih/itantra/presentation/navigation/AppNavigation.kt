package org.sih.itantra.presentation.navigation

import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.emergency.EmergencyScreen
import org.sih.itantra.presentation.network.NetworkScreen
import org.sih.itantra.presentation.onboarding.UserRegistrationScreen
import org.sih.itantra.presentation.profile.ProfileScreen
import org.sih.itantra.presentation.splash.SplashScreen
import org.sih.itantra.presentation.talk.TalkScreen
import org.sih.itantra.presentation.ui.components.TacticalTopBar
import org.sih.itantra.presentation.ui.components.IncomingTransmissionBanner
import org.sih.itantra.presentation.ui.theme.*
import org.sih.itantra.service.SosNavigationBus

/**
 * Tactical Navigation Architecture:
 * - Splash: Multi-stage cinematic AKHET assembly
 * - 1st Page: Operator Registration (Name, Gender, Preferred 10-Language Selection)
 * - 4 Dashboards:
 *   1. CONVO: Push-To-Talk + Pre-Commands + Speech-To-Text Stream
 *   2. SOS: Multi-Hop Wi-Fi Emergency Broadcast to All Devices
 *   3. MESH: 360° Radar Sweep, Connected Device Counter & Private Peer Selection
 *   4. PROFILE: User Identity & Dynamic 10-Language Switcher
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Splash : Screen("splash", "SPLASH", Icons.Default.Sensors)
    data object Register : Screen("register", "Register", Icons.Default.AppRegistration)
    data object Talk : Screen("talk", "Talk", Icons.Default.RecordVoiceOver)
    data object Emergency : Screen("emergency", "SOS", Icons.Default.Warning)
    data object Network : Screen("network", "Mesh", Icons.Default.Hub)
    data object Profile : Screen("profile", "Me", Icons.Default.Person)
}

val DashboardNavItems = listOf(
    Screen.Talk,
    Screen.Emergency,
    Screen.Network,
    Screen.Profile
)

private val NavOrder = listOf(
    Screen.Talk.route,
    Screen.Emergency.route,
    Screen.Network.route,
    Screen.Profile.route
)

private fun getSlideDirection(initialRoute: String?, targetRoute: String?): Int {
    val initialIdx = NavOrder.indexOf(initialRoute)
    val targetIdx = NavOrder.indexOf(targetRoute)
    return if (initialIdx != -1 && targetIdx != -1) {
        if (targetIdx > initialIdx) 1 else -1
    } else 0
}

@Composable
fun AppNavigation(repository: CommunicationRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val userProfile by repository.userProfile.collectAsState()
    val selectedLanguage by repository.selectedLanguage.collectAsState()
    val isSosActive by repository.emergencyManager.isSosActive.collectAsState()
    val activeTargetNode by repository.activeTargetNode.collectAsState()
    val incoming by repository.incoming.collectAsState()
    val unreadCount by repository.unreadCount.collectAsState()
    val ttsSpeaking by repository.ttsEngine.isSpeaking.collectAsState()

    val isFullScreen = currentRoute == Screen.Splash.route || currentRoute == Screen.Register.route

    // Tapping the SOS notification (app was backgrounded/closed) jumps straight to the Emergency
    // tab, once the user is past Splash/Registration and the nav graph is actually ready for it.
    val pendingSosOpen by SosNavigationBus.pendingOpenEmergency.collectAsState()
    LaunchedEffect(pendingSosOpen, currentRoute, userProfile.isInitialized) {
        if (pendingSosOpen && userProfile.isInitialized && currentRoute != null && !isFullScreen) {
            SosNavigationBus.pendingOpenEmergency.value = false
            if (currentRoute != Screen.Emergency.route) {
                navController.navigate(Screen.Emergency.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(currentRoute, unreadCount) {
        if (currentRoute == Screen.Talk.route && unreadCount > 0) repository.markIncomingSeen()
    }
    LaunchedEffect(incoming?.messageId, incoming?.spoken) {
        val shown = incoming ?: return@LaunchedEffect
        // Stay up while the voice note plays, then 4 s more; give up after 12 s (30 s for SOS)
        // in case no voice pack is installed and playback never reports completion.
        kotlinx.coroutines.delay(if (shown.spoken) 4_000L else if (shown.isSos) 30_000L else 12_000L)
        repository.dismissIncoming(shown.messageId)
    }

    val context = LocalContext.current
    val reduceMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TacticalTopBar(
                    selectedLanguage = selectedLanguage,
                    isSosActive = isSosActive,
                    userProfile = userProfile,
                    activeTargetNode = activeTargetNode,
                    onOpenLanguage = { navController.navigate(Screen.Profile.route) },
                    onTriggerSos = { navController.navigate(Screen.Emergency.route) },
                    onClearTarget = { repository.clearPrivatePeer() }
                )
            }
        },
        bottomBar = {
            if (!isFullScreen) {
                val selectedIndex = NavOrder.indexOf(currentRoute).coerceAtLeast(0)
                val barOffsetRatio by animateFloatAsState(
                    targetValue = selectedIndex.toFloat(),
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    label = "navBarOffset"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LabCard)
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(LabInk))
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp)
                    ) {
                        val tabWidth = maxWidth / DashboardNavItems.size

                        // Sliding orange block marking the active tab
                        Box(
                            modifier = Modifier
                                .offset(x = tabWidth * barOffsetRatio)
                                .width(tabWidth)
                                .height(4.dp)
                                .background(LabOrange)
                        )

                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DashboardNavItems.forEachIndexed { i, screen ->
                                val isSelected = currentRoute == screen.route
                                val isEmergency = screen == Screen.Emergency
                                val labelColor by animateColorAsState(
                                    targetValue = when {
                                        isSelected -> LabInk
                                        isEmergency -> LabOrangeDeep
                                        else -> LabInkDim
                                    },
                                    animationSpec = tween(200),
                                    label = "tabLabelColor"
                                )

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (currentRoute != screen.route) {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        }
                                        .padding(start = 12.dp, top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "%02d".format(i + 1),
                                                fontFamily = LabFont,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp,
                                                color = if (isSelected) LabOrangeDeep else LabInkDim
                                            )
                                            if (screen == Screen.Talk && unreadCount > 0 && !isSelected) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .background(LabOrange)
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                                        fontFamily = LabFont,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = LabInk
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = screen.label.uppercase(),
                                            fontFamily = LabFont,
                                            fontSize = 16.sp,
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            letterSpacing = (-0.4).sp,
                                            color = labelColor
                                        )
                                    }
                                }
                                if (i < DashboardNavItems.lastIndex) {
                                    Box(
                                        Modifier
                                            .padding(vertical = 16.dp)
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(LabHairline)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = LabPaper
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(200))
                    } else if (initialState.destination.route == Screen.Splash.route) {
                        fadeIn(animationSpec = tween(400))
                    } else {
                        val dir = getSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        if (dir != 0) {
                            slideInHorizontally(
                                initialOffsetX = { fullWidth -> dir * fullWidth },
                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing))
                        } else {
                            fadeIn(animationSpec = tween(280))
                        }
                    }
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(200))
                    } else if (initialState.destination.route == Screen.Splash.route) {
                        fadeOut(animationSpec = tween(300))
                    } else {
                        val dir = getSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        if (dir != 0) {
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> -dir * fullWidth },
                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing))
                        } else {
                            fadeOut(animationSpec = tween(280))
                        }
                    }
                }
            ) {
                // Splash Screen: Cinematic Launch Sequence
                composable(Screen.Splash.route) {
                    SplashScreen(
                        onNavigateNext = {
                            val destination = if (userProfile.isInitialized) Screen.Talk.route else Screen.Register.route
                            navController.navigate(destination) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                    )
                }

                // First Page: User Onboarding / Registration
                composable(Screen.Register.route) {
                    UserRegistrationScreen(
                        repository = repository,
                        onRegistrationComplete = {
                            navController.navigate(Screen.Talk.route) {
                                popUpTo(Screen.Register.route) { inclusive = true }
                            }
                        }
                    )
                }

                // Dashboard 1: CONVO (Push-To-Talk + Pre-Commands)
                composable(Screen.Talk.route) {
                    TalkScreen(repository = repository)
                }

                // Dashboard 2: SOS (Wi-Fi Emergency Broadcast to All)
                composable(Screen.Emergency.route) {
                    EmergencyScreen(repository = repository)
                }

                // Dashboard 3: MESH (Radar + Device Count + Private Peer Selection)
                composable(Screen.Network.route) {
                    NetworkScreen(repository = repository)
                }

                // Dashboard 4: PROFILE (User Details + 10-Language Switcher)
                composable(Screen.Profile.route) {
                    ProfileScreen(repository = repository)
                }
            }

            // Receive-side feedback: banner over any screen while a message arrives and plays
            AnimatedVisibility(
                visible = incoming != null && !isFullScreen,
                enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Keep the last value during the exit animation
                var last by remember { mutableStateOf(incoming) }
                if (incoming != null) last = incoming
                last?.let { msg ->
                    IncomingTransmissionBanner(
                        transmission = msg,
                        isPlaying = ttsSpeaking,
                        onOpen = {
                            repository.dismissIncoming(msg.messageId)
                            val target = if (msg.isSos) Screen.Emergency.route else Screen.Talk.route
                            if (currentRoute != target && !isFullScreen) {
                                navController.navigate(target) { launchSingleTop = true }
                            }
                        },
                        onDismiss = { repository.dismissIncoming(msg.messageId) }
                    )
                }
            }
        }
    }
}

