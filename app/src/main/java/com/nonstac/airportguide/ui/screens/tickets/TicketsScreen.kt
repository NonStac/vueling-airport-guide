package com.nonstac.airportguide.ui.screens.tickets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ConnectingAirports
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.FlightClass
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonstac.airportguide.data.model.Ticket
import androidx.compose.material.icons.automirrored.filled.Chat
import com.nonstac.airportguide.ui.theme.OnPrimaryLight
import com.nonstac.airportguide.ui.theme.VuelingDarkGray
import com.nonstac.airportguide.ui.theme.VuelingGray
import com.nonstac.airportguide.ui.theme.VuelingYellow


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen(
    username: String,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    ticketsViewModel: TicketsViewModel = viewModel(factory = TicketsViewModel.provideFactory(username))
) {
    val uiState by ticketsViewModel.uiState.collectAsStateWithLifecycle()
    var showAvailable by remember { mutableStateOf(false) } // Toggle between bought/available

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            ticketsViewModel.clearError() // Consume the error
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (showAvailable) "Available Flights" else "My Vueling Tickets") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VuelingDarkGray,
                    titleContentColor = OnPrimaryLight
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // Segmented Button or Tabs to switch views
            SegmentedButtonToggle(
                selectedOption = if (showAvailable) "Available" else "My Tickets",
                options = listOf("My Tickets", "Available"),
                onOptionSelected = { showAvailable = (it == "Available") }
            )


            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val ticketsToShow = if (showAvailable) uiState.availableTickets else uiState.boughtTickets
                    if (ticketsToShow.isEmpty()) {
                        item {
                            Text(
                                text = if (showAvailable) "No available flights found for this mock search." else "You haven't bought any tickets yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        items(ticketsToShow, key = { it.id }) { ticket ->
                            TicketItem(
                                ticket = ticket,
                                isAvailable = showAvailable,
                                onBuyClick = {
                                    ticketsViewModel.buyTicket(ticket.id)
                                },
                                isBuying = uiState.isBuyingTicketId == ticket.id,
                                onChatClick = { onNavigateToChat(ticket.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedButtonToggle(
    selectedOption: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = { onOptionSelected(label) },
                selected = label == selectedOption,
                icon = {}, // Optional icon
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = VuelingYellow,
                    activeContentColor = VuelingDarkGray,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(label)
            }
        }
    }
}


@Composable
fun TicketItem(ticket: Ticket, isAvailable: Boolean, onBuyClick: () -> Unit, isBuying: Boolean, onChatClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // Header with Airline Logo and Flight Info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VuelingGray) // Or primaryContainer
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Replace Text with actual Vueling Logo if available
                Text(
                    text = ticket.airline, // "VUELING"
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = VuelingYellow // Or onPrimary
                )
                Text(
                    text = "Flight ${ticket.flightNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White // Or onPrimary
                )
            }

            // Body with Flight Details
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Origin
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ticket.originAirportCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(formatTime(ticket.departureTime), style = MaterialTheme.typography.bodyMedium)
                    Text("Departure", style = MaterialTheme.typography.labelSmall)
                }

                // Icon
                Icon(
                    Icons.Filled.ConnectingAirports,
                    contentDescription = "Flight path",
                    tint = VuelingGray,
                    modifier = Modifier.size(32.dp).padding(horizontal = 8.dp)
                )


                // Destination
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ticket.destinationAirportCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(formatTime(ticket.arrivalTime), style = MaterialTheme.typography.bodyMedium)
                    Text("Arrival", style = MaterialTheme.typography.labelSmall)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Footer with Gate/Seat or Buy Button
            if (isAvailable) {
                Button(
                    onClick = onBuyClick,
                    modifier = Modifier.align(Alignment.End).padding(16.dp),
                    enabled = !isBuying,
                    colors = ButtonDefaults.buttonColors(containerColor = VuelingYellow, contentColor = VuelingDarkGray)
                ) {
                    if (isBuying) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Processing...", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        Icon(Icons.Default.FlightTakeoff, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Buy Ticket", color = VuelingDarkGray)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FlightClass, contentDescription = "Seat", modifier = Modifier.size(20.dp), tint = VuelingGray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Seat: ${ticket.seat}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MeetingRoom, contentDescription = "Gate", modifier = Modifier.size(20.dp), tint = VuelingGray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gate: ${ticket.gate ?: "TBD"}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                IconButton(onClick = onChatClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Open Chat",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// Helper to format time (improve as needed)
fun formatTime(dateTimeString: String?): String {
    if (dateTimeString == null) return "N/A"
    return try {
        // Basic parsing assuming ISO format like "2025-07-15T08:00:00Z"
        val timePart = dateTimeString.substringAfter('T').substringBeforeLast(':')
        timePart
    } catch (e: Exception) {
        "N/A"
    }
}