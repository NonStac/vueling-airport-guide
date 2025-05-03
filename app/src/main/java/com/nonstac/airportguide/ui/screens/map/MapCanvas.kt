package com.nonstac.airportguide.ui.screens.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nonstac.airportguide.data.model.AirportMap
import com.nonstac.airportguide.data.model.Node
import com.nonstac.airportguide.data.model.NodeType
import com.nonstac.airportguide.ui.theme.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Define constants for radii
private val NODE_RADIUS: Dp = 12.dp
private val HIGHLIGHTED_RADIUS: Dp = 18.dp
private val HIGHLIGHTED_BORDER_WIDTH: Dp = 6.dp
private const val CLICK_RADIUS_MULTIPLIER = 1.8f // Make clickable area larger than visual radius


@Composable
fun MapCanvas(
    map: AirportMap?,
    currentLocationNodeId: String?,
    destinationNodeId: String?,
    path: List<Node>?,
    currentFloor: Int,
    isBlackout: Boolean,
    modifier: Modifier = Modifier,
    onNodeClick: (Node) -> Unit // Add callback for node clicks
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    val nodesById = remember(map) { map?.nodes?.associateBy { it.id } ?: emptyMap() }

    val floorNodes = remember(nodesById, currentFloor) {
        nodesById.values.filter { it.floor == currentFloor }
    }

    // Pre-calculate radii in pixels based on density
    val nodeRadiusPx = remember(density) { with(density) { NODE_RADIUS.toPx() } }
    val highlightedRadiusPx = remember(density) { with(density) { HIGHLIGHTED_RADIUS.toPx() } }
    val clickRadiusBasePx = remember(nodeRadiusPx, highlightedRadiusPx) {
        max(nodeRadiusPx, highlightedRadiusPx) * CLICK_RADIUS_MULTIPLIER
    }
    val highlightedBorderWidthPx = remember(density) { with(density) { HIGHLIGHTED_BORDER_WIDTH.toPx() } }

    // --- Moved Calculations Outside DrawScope ---
    // Calculate floorEdges using remember in the Composable scope
    val floorEdges = remember(map, currentFloor, nodesById) {
        map?.edges?.filter { edge ->
            val fromNodeLocal = nodesById[edge.from]
            val toNodeLocal = nodesById[edge.to]
            // Draw edge if both nodes are on the current floor OR it's a stair connection involving this floor (to show stair nodes)
            (fromNodeLocal?.floor == currentFloor && toNodeLocal?.floor == currentFloor) ||
                    (edge.stairs && (fromNodeLocal?.floor == currentFloor || toNodeLocal?.floor == currentFloor))
        } ?: emptyList()
    }

    // Calculate floorPath using remember in the Composable scope
    val floorPath = remember(path, currentFloor) {
        path?.filter { node -> node.floor == currentFloor }
    }

    // Get icon color in the Composable scope
    val specialIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    // --- End Moved Calculations ---


    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { canvasSize = it }
            .pointerInput(floorNodes, canvasSize, nodesById, currentFloor, map, onNodeClick) { // Update dependencies
                detectTapGestures(
                    onTap = { clickOffset ->
                        // Keep floorNodes read from the outer scope
                        if (map == null || floorNodes.isEmpty() || canvasSize == IntSize.Zero) return@detectTapGestures

                        // --- Inverse Calculation (Recalculate scale/offset based on current tap size) ---
                        val padding = 60f
                        val availableWidth = size.width - 2 * padding
                        val availableHeight = size.height - 2 * padding

                        val minX = floorNodes.minOfOrNull { node -> node.x }?.toFloat() ?: 0f
                        val maxX = floorNodes.maxOfOrNull { node -> node.x }?.toFloat() ?: 1f
                        val minY = floorNodes.minOfOrNull { node -> node.y }?.toFloat() ?: 0f
                        val maxY = floorNodes.maxOfOrNull { node -> node.y }?.toFloat() ?: 1f

                        val mapWidth = max(1f, maxX - minX)
                        val mapHeight = max(1f, maxY - minY)

                        val scaleX = if (mapWidth > 0) availableWidth / mapWidth else 1f
                        val scaleY = if (mapHeight > 0) availableHeight / mapHeight else 1f
                        val scale = min(scaleX, scaleY) * 0.95f

                        val scaledMapWidth = mapWidth * scale
                        val scaledMapHeight = mapHeight * scale
                        val offsetX = padding + (availableWidth - scaledMapWidth) / 2f
                        val offsetY = padding + (availableHeight - scaledMapHeight) / 2f
                        // --- End Recalculation ---


                        // Find clicked node
                        var clickedNode: Node? = null
                        val clickRadiusSquared = clickRadiusBasePx.pow(2) // Use squared distance

                        for (node in floorNodes.reversed()) { // Iterate potential targets
                            // Calculate node's position on canvas using the same logic as drawing
                            val nodeCanvasX = offsetX + (node.x - minX) * scale
                            val nodeCanvasY = offsetY + (node.y - minY) * scale

                            val distanceSquared = (clickOffset.x - nodeCanvasX).pow(2) +
                                    (clickOffset.y - nodeCanvasY).pow(2)

                            if (distanceSquared <= clickRadiusSquared) {
                                clickedNode = node
                                break // Found the topmost node
                            }
                        }

                        clickedNode?.let(onNodeClick) // Notify using function reference
                    }
                )
            }
    ) { // Start of DrawScope - THIS IS NOT @Composable
        if (map == null || floorNodes.isEmpty() || canvasSize == IntSize.Zero) {
            return@Canvas
        }

        // --- Calculate Scaling and Offset (Same logic as in pointerInput) ---
        // This calculation needs to be done inside DrawScope because it depends on `size`
        val padding = 60f
        val availableWidth = size.width - 2 * padding
        val availableHeight = size.height - 2 * padding
        // Use floorNodes from the outer scope
        val minX = floorNodes.minOfOrNull { node -> node.x }?.toFloat() ?: 0f
        val maxX = floorNodes.maxOfOrNull { node -> node.x }?.toFloat() ?: 1f
        val minY = floorNodes.minOfOrNull { node -> node.y }?.toFloat() ?: 0f
        val maxY = floorNodes.maxOfOrNull { node -> node.y }?.toFloat() ?: 1f
        val mapWidth = max(1f, maxX - minX)
        val mapHeight = max(1f, maxY - minY)
        val scaleX = if (mapWidth > 0) availableWidth / mapWidth else 1f
        val scaleY = if (mapHeight > 0) availableHeight / mapHeight else 1f
        val scale = min(scaleX, scaleY) * 0.95f
        val scaledMapWidth = mapWidth * scale
        val scaledMapHeight = mapHeight * scale
        val offsetX = padding + (availableWidth - scaledMapWidth) / 2f
        val offsetY = padding + (availableHeight - scaledMapHeight) / 2f
        // --- End Calculation ---

        // Helper function (closure captures scale, offsetX, offsetY, minX, minY)
        fun Node.toOffset(): Offset {
            val canvasX = offsetX + (this.x - minX) * scale
            val canvasY = offsetY + (this.y - minY) * scale
            return Offset(canvasX, canvasY)
        }

        // --- Draw Edges ---
        val edgeStrokeWidth = 4f
        val pathStrokeWidth = 10f
        val dashedPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        // Use floorEdges calculated outside DrawScope
        floorEdges.forEach { edge ->
            val fromNodeLocal = nodesById[edge.from] // nodesById from outer scope
            val toNodeLocal = nodesById[edge.to]
            if (fromNodeLocal != null && toNodeLocal != null) {
                if (fromNodeLocal.floor == currentFloor && toNodeLocal.floor == currentFloor) {
                    val startOffset = fromNodeLocal.toOffset()
                    val endOffset = toNodeLocal.toOffset()
                    drawLine(
                        color = EdgeColorDefault,
                        start = startOffset,
                        end = endOffset,
                        strokeWidth = edgeStrokeWidth,
                        pathEffect = if (edge.stairs) dashedPathEffect else null
                    )
                }
                // Optionally draw partial lines or just nodes for stairs connecting floors
            }
        }

        // --- Draw Path ---
        // Use floorPath calculated outside DrawScope
        if (!floorPath.isNullOrEmpty() && floorPath.size >= 2) {
            for (i in 0 until floorPath.size - 1) {
                val pathNode1 = floorPath[i]
                val pathNode2 = floorPath[i+1]
                val edgeInfo = map.edges.find { // map from outer scope
                    (it.from == pathNode1.id && it.to == pathNode2.id) || (it.from == pathNode2.id && it.to == pathNode1.id)
                }
                drawLine(
                    color = PathColor,
                    start = pathNode1.toOffset(),
                    end = pathNode2.toOffset(),
                    strokeWidth = pathStrokeWidth,
                    cap = StrokeCap.Round,
                    pathEffect = if (edgeInfo?.stairs == true) dashedPathEffect else null
                )
            }
        }


        // --- Draw Nodes --- (Use floorNodes from outer scope)
        floorNodes.forEach { node ->
            val offset = node.toOffset()
            val isCurrent = node.id == currentLocationNodeId
            val isDestination = node.id == destinationNodeId

            val radiusPx = if (isCurrent || isDestination) highlightedRadiusPx else nodeRadiusPx
            val nodeColor = getNodeColor(node.type)

            // Draw border/highlight effect
            if (isCurrent) {
                drawCircle(
                    color = NodeColorCurrentLocation.copy(alpha = 0.3f),
                    radius = radiusPx + highlightedBorderWidthPx + 4f,
                    center = offset
                )
                drawCircle(
                    color = NodeColorCurrentLocation,
                    radius = radiusPx + highlightedBorderWidthPx,
                    center = offset
                )
            } else if (isDestination) {
                drawCircle(
                    color = NodeColorDestination.copy(alpha = 0.8f),
                    radius = radiusPx + highlightedBorderWidthPx,
                    center = offset
                )
            }

            // Draw main node circle
            drawCircle(
                color = nodeColor,
                radius = radiusPx,
                center = offset
            )

            // Draw special icons using the color fetched outside DrawScope
            if (node.name.contains("Stairs", ignoreCase = true) || node.name.contains("Elevator", ignoreCase = true)) {
                drawCircle(
                    color = specialIconColor, // Use the pre-fetched color
                    radius = nodeRadiusPx * 0.4f,
                    center = offset.copy(y = offset.y - nodeRadiusPx * 0.8f)
                )
            }
        }

        // --- Draw Blackout Overlay ---
        if (isBlackout) {
            drawRect(
                color = BlackoutOverlay,
                size = size
            )
        }
    } // End of DrawScope
}

// Helper function remains the same
private fun getNodeColor(type: NodeType): Color {
    return when (type) {
        NodeType.ENTRANCE -> NodeColorEntrance
        NodeType.GATE -> NodeColorGate
        NodeType.BATHROOM -> NodeColorBathroom
        NodeType.EMERGENCY_EXIT -> NodeColorExit
        NodeType.WAYPOINT -> NodeColorDefault
    }
}