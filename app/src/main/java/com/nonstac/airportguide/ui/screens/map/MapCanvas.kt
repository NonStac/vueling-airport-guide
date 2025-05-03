package com.nonstac.airportguide.ui.screens.map

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.nonstac.airportguide.data.model.AirportMap
import com.nonstac.airportguide.data.model.Node
import com.nonstac.airportguide.data.model.NodeType
import com.nonstac.airportguide.ui.theme.*
import kotlin.math.max
import kotlin.math.min

@Composable
fun MapCanvas(
    map: AirportMap?,
    currentLocationNodeId: String?,
    destinationNodeId: String?,
    path: List<Node>?,
    currentFloor: Int,
    isBlackout: Boolean,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val nodesById = remember(map) { map?.nodes?.associateBy { it.id } ?: emptyMap() }
    val edgesByFromId = remember(map) { map?.edges?.groupBy { it.from } ?: emptyMap() }

    // Filter nodes and edges for the current floor
    val floorNodes = remember(nodesById, currentFloor) {
        nodesById.values.filter { it.floor == currentFloor }
    }
    val floorEdges = remember(map, currentFloor, nodesById) {
        map?.edges?.filter { edge ->
            val fromNode = nodesById[edge.from]
            val toNode = nodesById[edge.to]
            // Only draw edge if both nodes are on the current floor OR it's a stair connection involving this floor
            (fromNode?.floor == currentFloor && toNode?.floor == currentFloor) ||
                    (edge.stairs && (fromNode?.floor == currentFloor || toNode?.floor == currentFloor)) // Show stair nodes
        } ?: emptyList()
    }
    val floorPath = remember(path, currentFloor) {
        path?.filter { it.floor == currentFloor }
    }


    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { canvasSize = it }
    ) {
        if (map == null || floorNodes.isEmpty() || canvasSize == IntSize.Zero) {
            // Draw loading or empty state if needed
            return@Canvas
        }

        // --- Calculate Scaling and Offset ---
        val padding = 60f // Padding around the map edges in pixels
        val availableWidth = size.width - 2 * padding
        val availableHeight = size.height - 2 * padding

        val minX = floorNodes.minOfOrNull { it.x }?.toFloat() ?: 0f
        val maxX = floorNodes.maxOfOrNull { it.x }?.toFloat() ?: 1f
        val minY = floorNodes.minOfOrNull { it.y }?.toFloat() ?: 0f
        val maxY = floorNodes.maxOfOrNull { it.y }?.toFloat() ?: 1f

        val mapWidth = max(1f, maxX - minX)
        val mapHeight = max(1f, maxY - minY)

        val scaleX = availableWidth / mapWidth
        val scaleY = availableHeight / mapHeight
        val scale = min(scaleX, scaleY) * 0.95f // Use slightly smaller scale to ensure fit

        // Center the map
        val scaledMapWidth = mapWidth * scale
        val scaledMapHeight = mapHeight * scale
        val offsetX = padding + (availableWidth - scaledMapWidth) / 2f
        val offsetY = padding + (availableHeight - scaledMapHeight) / 2f

        // Helper function to transform map coordinates to canvas coordinates
        fun Node.toOffset(): Offset {
            val canvasX = offsetX + (this.x - minX) * scale
            val canvasY = offsetY + (this.y - minY) * scale
            return Offset(canvasX, canvasY)
        }

        // --- Draw Edges ---
        val edgeStrokeWidth = 4f
        val pathStrokeWidth = 10f
        val dashedPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        floorEdges.forEach { edge ->
            val fromNode = nodesById[edge.from]
            val toNode = nodesById[edge.to]
            if (fromNode != null && toNode != null) {
                // Only draw if both ends are on the current floor for regular edges
                if (fromNode.floor == currentFloor && toNode.floor == currentFloor) {
                    val startOffset = fromNode.toOffset()
                    val endOffset = toNode.toOffset()
                    drawLine(
                        color = EdgeColorDefault,
                        start = startOffset,
                        end = endOffset,
                        strokeWidth = edgeStrokeWidth,
                        pathEffect = if (edge.stairs) dashedPathEffect else null
                    )
                }
            }
        }

        // --- Draw Path ---
        if (!floorPath.isNullOrEmpty() && floorPath.size >= 2) {
            for (i in 0 until floorPath.size - 1) {
                val pathNode1 = floorPath[i]
                val pathNode2 = floorPath[i+1]
                val edgeInfo = map.edges.find { (it.from == pathNode1.id && it.to == pathNode2.id) || (it.from == pathNode2.id && it.to == pathNode1.id) }
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


        // --- Draw Nodes ---
        val nodeRadius = 12f
        val highlightedRadius = 18f
        val highlightedBorderWidth = 6f


        floorNodes.forEach { node  ->
            val offset = node.toOffset()
            val isCurrent = node.id == currentLocationNodeId
            val isDestination = node.id == destinationNodeId

            val radius = if (isCurrent || isDestination) highlightedRadius else nodeRadius
            val color = when {
                isCurrent -> NodeColorCurrentLocation
                isDestination -> NodeColorDestination // Keep destination distinct
                else -> getNodeColor(node.type)
            }

            // Draw border for current/destination
            if (isCurrent) {
                drawCircle(
                    color = NodeColorCurrentLocation.copy(alpha=0.4f),
                    radius = radius + highlightedBorderWidth + 4f,
                    center = offset
                )
                drawCircle(
                    color = NodeColorCurrentLocation,
                    radius = radius + highlightedBorderWidth,
                    center = offset
                )
            } else if(isDestination) {
                drawCircle(
                    color = NodeColorDestination,
                    radius = radius + highlightedBorderWidth,
                    center = offset
                )
            }


            // Draw main node circle
            drawCircle(
                color = color,
                radius = radius,
                center = offset
            )

            drawSpecialIconForNode(node, offset, nodeRadius,this)
        }
        if (isBlackout) {
            drawRect(color = BlackoutOverlay)
        }
    }

}


private fun drawSpecialIconForNode(node: Node, offset: Offset, nodeRadius: Float, drawScope: DrawScope) {
    if (node.name.contains("Stairs", ignoreCase = true) || node.name.contains("Elevator", ignoreCase = true)) {
        val iconRadius = nodeRadius * 0.4f
        val iconOffset = nodeRadius * 0.8f
        drawScope.drawCircle(center = offset.copy(y = offset.y - iconOffset), radius = iconRadius, color = Color.Gray.copy(0.4f))
    }
}

private fun getNodeColor(type: NodeType): Color {
    return when (type) {
        NodeType.ENTRANCE -> NodeColorEntrance
        NodeType.GATE -> NodeColorGate
        NodeType.BATHROOM -> NodeColorBathroom
        NodeType.EMERGENCY_EXIT -> NodeColorExit
        NodeType.WAYPOINT -> NodeColorDefault
    }
}