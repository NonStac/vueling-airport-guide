package com.nonstac.airportguide.util

import com.nonstac.airportguide.data.model.Node
import kotlin.math.sqrt

object LocationUtils {
    // Simple Euclidean distance for map coordinates
    fun calculateDistance(node1: Node, node2: Node): Double {
        if (node1.floor != node2.floor) {
            // Add a penalty for changing floors - adjust as needed
            return Double.MAX_VALUE / 2 // Make floor changes very costly unless via specific edges
        }
        val dx = (node1.x - node2.x).toDouble()
        val dy = (node1.y - node2.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    // Heuristic function for A* (Euclidean distance)
    fun heuristic(node1: Node, node2: Node): Double {
        // Ignore floor for heuristic, A* will handle floor changes via edge costs
        val dx = (node1.x - node2.x).toDouble()
        val dy = (node1.y - node2.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    // Finds the node in the map closest to given coordinates (simplified)
    // In a real app, you'd need a more sophisticated indoor positioning system
    // or mapping of GPS ranges to areas/nodes. This just finds the closest by XY.
    fun findClosestNode(x: Double, y: Double, floor: Int, nodes: List<Node>): Node? {
        return nodes.filter { it.floor == floor } // Only consider nodes on the same floor
            .minByOrNull { node ->
                val dx = (node.x - x)
                val dy = (node.y - y)
                dx*dx + dy*dy // Use squared distance for comparison efficiency
            }
    }

    // Overload for finding closest node based on another node (e.g., finding nearest bathroom)
    fun findClosestNodeOfType(sourceNode: Node, type: com.nonstac.airportguide.data.model.NodeType, nodes: List<Node>): Node? {
        return nodes
            .filter { it.type == type }
            .minByOrNull { node -> calculateDistance(sourceNode, node) } // Use actual distance here
    }


    // Estimate distance along a path (sum of segment distances)
    fun calculatePathDistance(path: List<Node>): Double {
        if (path.size < 2) return 0.0
        var totalDistance = 0.0
        for (i in 0 until path.size - 1) {
            totalDistance += calculateDistance(path[i], path[i + 1])
        }
        // Add cost for stairs if any edge in the path requires it (needs graph access - better done in A*)
        // This simple version just sums Euclidean distances between nodes in the path.
        return totalDistance
    }
}