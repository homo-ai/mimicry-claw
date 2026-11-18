package com.mimicryos.agent.mcp

import com.mimicryos.agent.core.*
import java.util.concurrent.ConcurrentHashMap

class MCPManager {
    private val servers = ConcurrentHashMap<String, MCPServerConfig>()
    private val clients = ConcurrentHashMap<String, MCPClient>()
    private val tools = mutableListOf<Tool<*, *>>()

    fun addServer(config: MCPServerConfig) { servers[config.name] = config }
    suspend fun connect(serverName: String): Boolean { val config = servers[serverName] ?: return false; return try { val client = MCPClient(config); clients[serverName] = client; client.connect(); val serverTools = client.listTools(); tools.addAll(serverTools); true } catch (e: Exception) { false } }
    suspend fun disconnect(serverName: String) { clients[serverName]?.disconnect(); clients.remove(serverName) }
    fun getTools(): List<Tool<*, *>> = tools.toList()
    fun getClients(): List<MCPServerConnection> = clients.values.map { it.connection }
    fun getServerStatus(serverName: String): MCPServerStatus { val client = clients[serverName]; return MCPServerStatus(serverName, if (client != null) "connected" else "disconnected") }

    data class MCPServerConfig(val name: String, val command: String, val args: List<String> = emptyList(), val env: Map<String, String> = emptyMap(), val transport: String = "stdio")
    data class MCPServerStatus(val name: String, val status: String, val error: String? = null)
    private class MCPClient(private val config: MCPServerConfig) {
        val connection: MCPServerConnection get() = MCPServerConnection(config.name, config.command, config.args, config.env, config.transport)
        suspend fun connect() {}
        suspend fun disconnect() {}
        suspend fun listTools(): List<Tool<*, *>> = emptyList()
    }
}
