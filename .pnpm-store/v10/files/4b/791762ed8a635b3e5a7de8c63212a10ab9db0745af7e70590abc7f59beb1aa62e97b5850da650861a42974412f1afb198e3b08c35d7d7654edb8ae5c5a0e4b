import type { Logger, I18nextToolkitConfig, InstrumenterOptions, CandidateString, InstrumentationResults } from '../../types.js';
/**
 * Main orchestrator for the instrument command.
 * Scans source files for hardcoded strings and instruments them with i18next calls.
 *
 * @param config - Toolkit configuration
 * @param options - Instrumentation options (dry-run, interactive, etc.)
 * @param logger - Logger instance
 * @returns Instrumentation results
 */
export declare function runInstrumenter(config: I18nextToolkitConfig, options: InstrumenterOptions, logger?: Logger): Promise<InstrumentationResults>;
/**
 * Extracts and writes translation keys discovered during instrumentation.
 */
export declare function writeExtractedKeys(candidates: CandidateString[], config: I18nextToolkitConfig, namespace?: string, logger?: Logger): Promise<void>;
//# sourceMappingURL=instrumenter.d.ts.map