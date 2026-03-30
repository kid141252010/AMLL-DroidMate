import { EventEmitter } from 'node:events';
import type { I18nextToolkitConfig, Logger, LintIssue } from './types.js';
type LinterEventMap = {
    progress: [
        {
            message: string;
        }
    ];
    done: [
        {
            success: boolean;
            message: string;
            files: Record<string, LintIssue[]>;
        }
    ];
    error: [error: Error];
};
export declare const recommendedAcceptedTags: string[];
export declare const recommendedAcceptedAttributes: string[];
export declare class Linter extends EventEmitter<LinterEventMap> {
    private config;
    private logger;
    constructor(config: I18nextToolkitConfig, logger?: Logger);
    wrapError(error: unknown): Error;
    run(): Promise<{
        success: boolean;
        message: string;
        files: {
            [k: string]: LintIssue[];
        };
    }>;
    private createLintPluginContext;
    private initializeLintPlugins;
    private normalizeExtension;
    private shouldRunLintPluginForFile;
    private runLintOnLoadPipeline;
    private runLintOnResultPipeline;
}
/**
 * Runs the i18next linter to detect hardcoded strings and other potential issues.
 *
 * This function performs static analysis on source files to identify:
 * - Hardcoded text strings in JSX elements
 * - Hardcoded strings in JSX attributes (like alt text, titles, etc.)
 * - Text that should be extracted for translation
 *
 * The linter respects configuration settings:
 * - Uses the same input patterns as the extractor
 * - Ignores content inside configured Trans components
 * - Skips technical content like script/style tags
 * - Identifies numeric values and interpolation syntax to avoid false positives
 *
 * @param config - The toolkit configuration with input patterns and component names
 *
 * @example
 * ```typescript
 * const config = {
 *   extract: {
 *     input: ['src/**\/*.{ts,tsx}'],
 *     transComponents: ['Trans', 'Translation']
 *   }
 * }
 *
 * await runLinter(config)
 * // Outputs issues found or success message
 * ```
 */
export declare function runLinter(config: I18nextToolkitConfig): Promise<{
    success: boolean;
    message: string;
    files: {
        [k: string]: LintIssue[];
    };
}>;
export declare function runLinterCli(config: I18nextToolkitConfig, options?: {
    quiet?: boolean;
    logger?: Logger;
}): Promise<void>;
export {};
//# sourceMappingURL=linter.d.ts.map