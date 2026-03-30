import type { I18nextToolkitConfig, CandidateString } from '../../types.js';
/**
 * Detects if a string is a candidate for translation based on confidence heuristics.
 * Returns null if the string should be skipped, otherwise returns a CandidateString
 * with a confidence score.
 *
 * When a custom scorer is provided via `config.extract.instrumentScorer`, it is
 * called after the built-in skip checks. The scorer can:
 * - Return a number (0-1) to override the confidence score
 * - Return `null` to force-skip the candidate
 * - Return `undefined` to fall back to the built-in heuristic
 *
 * **Important:** This uses heuristic-based detection and will not catch 100% of cases.
 * False positives and false negatives are expected. The results serve as a starting point
 * for manual review and refinement. Always review the generated transformations before
 * committing them to your codebase.
 *
 * @param content - The string content to evaluate
 * @param offset - Byte offset in file (normalized)
 * @param endOffset - End byte offset in file
 * @param file - Source file path
 * @param code - Full source code for context
 * @param config - Toolkit configuration
 * @returns CandidateString with confidence score, or null if should be skipped
 */
export declare function detectCandidate(content: string, offset: number, endOffset: number, file: string, code: string, config: Omit<I18nextToolkitConfig, 'plugins'>): CandidateString | null;
//# sourceMappingURL=string-detector.d.ts.map