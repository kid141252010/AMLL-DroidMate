import type { Module } from '@swc/core';
import type { PluginContext, I18nextToolkitConfig, Logger, ASTVisitorHooks, ScopeInfo } from '../../types.js';
import { ScopeManager } from '../parsers/scope-manager.js';
import { ExpressionResolver } from '../parsers/expression-resolver.js';
/**
 * AST visitor class that traverses JavaScript/TypeScript syntax trees to extract translation keys.
 *
 * This class implements a manual recursive walker that:
 * - Maintains scope information for tracking useTranslation and getFixedT calls
 * - Extracts keys from t() function calls with various argument patterns
 * - Handles JSX Trans components with complex children serialization
 * - Supports both string literals and selector API for type-safe keys
 * - Processes pluralization and context variants
 * - Manages namespace resolution from multiple sources
 *
 * The visitor respects configuration options for separators, function names,
 * component names, and other extraction settings.
 *
 * @example
 * ```typescript
 * const visitors = new ASTVisitors(config, pluginContext, logger)
 * visitors.visit(parsedAST)
 *
 * // The pluginContext will now contain all extracted keys
 * ```
 */
export declare class ASTVisitors {
    private readonly pluginContext;
    private readonly config;
    private readonly logger;
    private hooks;
    get objectKeys(): Set<string>;
    readonly scopeManager: ScopeManager;
    private readonly expressionResolver;
    private readonly callExpressionHandler;
    private readonly jsxHandler;
    private currentFile;
    private currentCode;
    /**
     * Creates a new AST visitor instance.
     *
     * @param config - Toolkit configuration with extraction settings
     * @param pluginContext - Context for adding discovered translation keys
     * @param logger - Logger for warnings and debug information
     */
    constructor(config: Omit<I18nextToolkitConfig, 'plugins'>, pluginContext: PluginContext, logger: Logger, hooks?: ASTVisitorHooks, expressionResolver?: ExpressionResolver);
    /**
     * Lightweight pre-scan pass: populates the shared constant / type-alias / array tables
     * (`sharedConstants` in ScopeManager; `sharedVariableTable` and `sharedTypeAliasTable`
     * in ExpressionResolver) WITHOUT performing any key extraction.
     *
     * Callers should invoke this for ALL source files before calling `visit()` for any file,
     * so that cross-file identifier references — e.g. `useTranslation(NS_CALENDAR)` where
     * `NS_CALENDAR` is exported from a separate constants file — are already resolved when
     * the hook call is encountered during the extraction pass.
     *
     * The per-file tables (variableTable, typeAliasTable) are reset on each call so that
     * local bindings from one file do not bleed into another; the shared tables accumulate
     * across all calls and are intentionally NOT cleared here.
     */
    preScanForConstants(node: Module): void;
    /**
     * Recursive walker used exclusively by `preScanForConstants`.
     * Dispatches only to constant-capturing handlers; never extracts translation keys.
     */
    private _walkForConstants;
    /**
     * Main entry point for AST traversal.
     * Creates a root scope and begins the recursive walk through the syntax tree.
     *
     * @param node - The root module node to traverse
     */
    visit(node: Module): void;
    /**
     * Recursively walks through AST nodes, handling scoping and visiting logic.
     *
     * This is the core traversal method that:
     * 1. Manages function scopes (enter/exit)
     * 2. Dispatches to specific handlers based on node type
     * 3. Recursively processes child nodes
     * 4. Maintains proper scope cleanup
     *
     * @param node - The current AST node to process
     *
     * @private
     */
    private walk;
    /**
     * If `node` is a call like `ARRAY.map(param => ...)` where ARRAY is a known
     * string-array constant, returns the callback's first parameter name and the
     * array values so the caller can inject a temporary variable binding.
     *
     * Also handles:
     *   `Object.keys(MAP).map/forEach(k => ...)`  → param bound to MAP's keys
     *   `Object.values(MAP).map/forEach(v => ...)` → param bound to MAP's values
     */
    private tryGetArrayIterationCallbackInfo;
    /**
     * Extracts the first callback parameter identifier from an iteration call node
     * and pairs it with the provided values array.
     */
    private extractCallbackParam;
    /**
     * Retrieves variable information from the scope chain.
     * Searches from innermost to outermost scope.
     *
     * @param name - Variable name to look up
     * @returns Scope information if found, undefined otherwise
     *
     * @private
     */
    getVarFromScope(name: string): ScopeInfo | undefined;
    /**
     * Sets the current file path and code used by the extractor.
     */
    setCurrentFile(file: string, code: string): void;
    /**
     * Returns the currently set file path.
     *
     * @returns The current file path as a string, or `undefined` if no file has been set.
     * @remarks
     * Use this to retrieve the file context that was previously set via `setCurrentFile`.
     */
    getCurrentFile(): string;
    /**
     * @returns The full source code string for the file currently under processing.
     */
    getCurrentCode(): string;
}
//# sourceMappingURL=ast-visitors.d.ts.map