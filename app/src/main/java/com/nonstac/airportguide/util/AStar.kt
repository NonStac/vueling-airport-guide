package com.nonstac.airportguide.util

import com.nonstac.airportguide.data.model.AirportMap
import com.nonstac.airportguide.data.model.Node
import java.util.*
import kotlin.collections.HashMap

object AStar {

    fun findPath(startNodeId: String, goalNodeId: String, map: AirportMap): List<Node>? {
        val nodes = map.nodes.associateBy { it.id }
        val edges = map.edges.groupBy { it.from }

        val startNode = nodes[startNodeId] ?: return null
        val goalNode = nodes[goalNodeId] ?: return null

        // Nodes already evaluated
        val closedSet = mutableSetOf<String>()
        // Nodes discovered but not yet evaluated
        val openSet = PriorityQueue<Pair<String, Double>>(compareBy { it.second }) // Node ID -> fScore
        // Map of nodeId to the node that precedes it on the cheapest path found so far
        val cameFrom = HashMap<String, String>()

        // Cost from start along best known path.
        val gScore = HashMap<String, Double>().withDefault { Double.POSITIVE_INFINITY }
        gScore[startNodeId] = 0.0

        // Estimated total cost from start to goal through y.
        val fScore = HashMap<String, Double>().withDefault { Double.POSITIVE_INFINITY }
        fScore[startNodeId] = LocationUtils.heuristic(startNode, goalNode)

        openSet.add(startNodeId to fScore.getValue(startNodeId))

        while (openSet.isNotEmpty()) {
            val (currentId, _) = openSet.poll() ?: break // Get node with lowest fScore

            if (currentId == goalNodeId) {
                return reconstructPath(cameFrom, currentId, nodes)
            }

            closedSet.add(currentId)

            val currentGScore = gScore.getValue(currentId)
            val currentNode = nodes[currentId] ?: continue

            edges[currentId]?.forEach { edge ->
                val neighborId = edge.to
                if (neighborId in closedSet) {
                    return@forEach // Ignore neighbors already evaluated
                }

                val neighborNode = nodes[neighborId] ?: return@forEach

                // Calculate tentative gScore (cost to reach neighbor through current)
                val distance = LocationUtils.calculateDistance(currentNode, neighborNode)
                // Add a significant penalty for using stairs if not desired or possible
                // For simplicity here, we just use geometric distance. A real implementation
                // might check user preferences or accessibility needs.
                // If edge.stairs is true, add a penalty if needed based on context.
                val edgeWeight = distance // + (if(edge.stairs) STAIR_PENALTY else 0.0)
                val tentativeGScore = currentGScore + edgeWeight


                val neighborGScore = gScore.getValue(neighborId)
                if (tentativeGScore < neighborGScore) {
                    // This path to neighbor is better than any previous one. Record it!
                    cameFrom[neighborId] = currentId
                    gScore[neighborId] = tentativeGScore
                    val neighborFScore = tentativeGScore + LocationUtils.heuristic(neighborNode, goalNode)
                    fScore[neighborId] = neighborFScore

                    // Add neighbor to open set if not already there, or update its priority
                    // A simple way is to remove and re-add, or use a structure supporting priority updates
                    val existing = openSet.find { it.first == neighborId }
                    if (existing != null) {
                        openSet.remove(existing)
                    }
                    openSet.add(neighborId to neighborFScore)
                }
            }
        }

        // Open set is empty but goal was never reached
        return null
    }

    private fun reconstructPath(
        cameFrom: Map<String, String>,
        currentId: String,
        nodes: Map<String, Node>
    ): List<Node> {
        val totalPath = mutableListOf<Node>()
        var current = currentId
        while (current in cameFrom) {
            nodes[current]?.let { totalPath.add(it) }
            current = cameFrom.getValue(current)
        }
        nodes[current]?.let { totalPath.add(it) } // Add the start node
        return totalPath.reversed()
    }
}