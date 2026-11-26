package net.talaatharb.expanse.mcp;

/**
 * Tool authorization policy. Read-only tools are always exposed. Mutating tools are exposed only when
 * {@code allowMutations} is set; operations that remove user data additionally need {@code allowDestructive}.
 */
public record McpPolicy(boolean allowMutations, boolean allowDestructive) {
    public static McpPolicy readOnly() {
        return new McpPolicy(false, false);
    }

    public static McpPolicy editing() {
        return new McpPolicy(true, false);
    }

    public static McpPolicy full() {
        return new McpPolicy(true, true);
    }
}
