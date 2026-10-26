package com.dreamdisplayx.platform.server.registrar

/**
 * Legacy (1.20.1) stand-in for the Paper `/display group` command node.
 *
 * Same reasoning as [CommandRegistrar]: the modern body is written against
 * `io.papermc.paper.command.brigadier`, which `paper-api:1.20.1` does not ship, and 1.20.1 only
 * builds the Fabric platform here, whose command tree never reaches this object.
 */
@Deprecated("Unavailable on 1.20.1; Paper 1.20.1 has no io.papermc.paper.command.brigadier API")
object GroupCommand