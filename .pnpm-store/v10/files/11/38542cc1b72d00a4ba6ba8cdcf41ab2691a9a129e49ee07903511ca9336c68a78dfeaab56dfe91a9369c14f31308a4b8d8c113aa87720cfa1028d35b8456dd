// src/index.ts
import browserslist from "browserslist";
import * as css from "lightningcss";
var fileRegex = /\.(css)$/;
function lightningcss(opts) {
  const defaultOptions = {
    minify: true,
    sourceMap: true
  };
  const { browserslist: browserslistOpts, ...lightningOpts } = opts ?? {};
  const targets = css.browserslistToTargets(browserslist(browserslistOpts));
  return [
    {
      name: "vite-plugin-lightningcss",
      transform(src, id) {
        if (fileRegex.test(id)) {
          const { code, map } = css.transform({
            filename: id,
            code: Buffer.from(src),
            ...defaultOptions,
            targets,
            ...lightningOpts
          });
          return {
            code: code.toString(),
            map: map ? map.toString() : void 0
          };
        }
      }
    }
  ];
}
var src_default = lightningcss;
export {
  src_default as default
};
