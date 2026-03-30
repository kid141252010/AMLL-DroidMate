import type { CustomAtRules, TransformOptions } from "lightningcss";
import { type Plugin } from "vite";
declare type ViteTransformOptions<C extends CustomAtRules> = Omit<TransformOptions<C>, "filename" | "code"> & {
  browserslist?: string | readonly string[];
};
declare function lightningcss<C extends CustomAtRules>(opts?: ViteTransformOptions<C>): Plugin[];
export default lightningcss;
