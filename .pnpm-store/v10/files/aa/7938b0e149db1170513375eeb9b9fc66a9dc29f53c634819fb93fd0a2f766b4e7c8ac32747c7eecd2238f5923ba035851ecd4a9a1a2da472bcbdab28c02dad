import { IgnoreLike } from 'glob';
import { LogLevel, Plugin } from 'vite';

declare const LogLevels: Record<LogLevel, number>;
interface Options {
    /**
     * Set to 'info' for noisy information.
     *
     * Default: 'warn'
     */
    logLevel?: LogLevel;
    /**
     * Glob patterns to match files
     *
     * Default: ['**\/*.json', '**\/*.yml', '**\/*.yaml']
     */
    include?: string[];
    /**
     * Glob patterns to exclude/ignore
     *
     * @see https://github.com/isaacs/node-glob
     */
    ignore?: string | string[] | IgnoreLike;
    /**
     * Locale top level directory paths ordered from least specialized to most specialized
     *  e.g. lib locale -> app locale
     *
     * Locales loaded later will overwrite any duplicated key via a deep merge strategy.
     */
    paths: string[];
    /**
     * Default: none
     */
    namespaceResolution?: 'basename' | 'relativePath';
}
interface ResBundle {
    [key: string]: string | object;
}
declare const factory: (options: Options) => Plugin<any>;

export { LogLevels, type Options, type ResBundle, factory as default };
