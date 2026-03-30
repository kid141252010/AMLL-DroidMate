var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// src/index.ts
var src_exports = {};
__export(src_exports, {
  default: () => src_default
});
module.exports = __toCommonJS(src_exports);
var import_browserslist = __toESM(require("browserslist"), 1);
var css = __toESM(require("lightningcss"), 1);
var fileRegex = /\.(css)$/;
function lightningcss(opts) {
  const defaultOptions = {
    minify: true,
    sourceMap: true
  };
  const { browserslist: browserslistOpts, ...lightningOpts } = opts ?? {};
  const targets = css.browserslistToTargets((0, import_browserslist.default)(browserslistOpts));
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
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {});
