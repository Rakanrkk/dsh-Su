import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";

const modulePath = process.env.DSH_WORKSPACE_FILES_MODULE;

test("workspace-files follows final file symlinks without broadening directory listing", {
  skip: modulePath === undefined ? "set DSH_WORKSPACE_FILES_MODULE to a patched DSH package" : false
}, async (t) => {
  const [{ WorkspaceFiles }, { Context }] = await Promise.all([
    import(pathToFileURL(modulePath).href),
    import(new URL("../../cordis/lib/index.js", pathToFileURL(modulePath)))
  ]);
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), "dsh-workspace-files-"));
  const root = path.join(parent, "workspace");
  const outside = path.join(parent, "outside");
  fs.mkdirSync(path.join(root, "nested"), { recursive: true });
  fs.mkdirSync(outside);
  const target = path.join(root, "target.txt");
  const bytes = Buffer.from([0, 65, 255, 10]);
  fs.writeFileSync(target, "fixture text\n");
  fs.writeFileSync(path.join(root, "nested", "payload.bin"), bytes);
  fs.writeFileSync(path.join(root, "nested", "base.txt"), "base\n");
  fs.writeFileSync(path.join(outside, "secret.txt"), "outside\n");
  fs.symlinkSync("target.txt", path.join(root, "relative.txt"));
  fs.symlinkSync(target, path.join(root, "absolute.txt"));
  fs.symlinkSync("nested/base.txt", path.join(root, "base-link.txt"));
  fs.symlinkSync("missing.txt", path.join(root, "broken.txt"));
  fs.symlinkSync("directory", path.join(root, "directory-link"));
  fs.mkdirSync(path.join(root, "directory"));
  fs.symlinkSync("cycle-b", path.join(root, "cycle-a"));
  fs.symlinkSync("cycle-a", path.join(root, "cycle-b"));
  fs.symlinkSync(outside, path.join(root, "outside-directory-link"));

  const backend = {
    lstat: async (value, options = {}) => {
      const absolute = path.resolve(options.cwd ?? root, value);
      try {
        const info = fs.lstatSync(absolute);
        return { type: info.isSymbolicLink() ? "symlink" : info.isDirectory() ? "directory" : info.isFile() ? "file" : "other" };
      } catch (error) {
        if (error.code === "ENOENT") return undefined;
        throw error;
      }
    },
    resolve: async (value, options = {}) => fs.realpathSync(path.resolve(options.cwd ?? root, value)),
    stat: async (value) => {
      try {
        const info = fs.statSync(value);
        return { type: info.isDirectory() ? "directory" : info.isFile() ? "file" : "other", size: info.size, version: String(info.mtimeMs) };
      } catch (error) {
        if (error.code === "ENOENT") return undefined;
        throw error;
      }
    },
    processPath: (value) => value,
    contains: (base, value) => {
      const relative = path.relative(base, value);
      return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
    },
    readBytes: async (value, signal, limit) => {
      const result = fs.readFileSync(value);
      if (result.length > limit) {
        const error = new Error("file too large");
        error.code = "FS_TOO_LARGE";
        throw error;
      }
      return result;
    },
    readByteRange: async (value, range) => fs.readFileSync(value).subarray(range.offset, range.offset + range.length),
    streamText: (value) => fs.createReadStream(value, { encoding: "utf8" })
  };
  const context = new Context();
  context.provide("fs", backend);
  const service = new WorkspaceFiles(context, { maxBytes: 1024, maxFileBytes: 1024, maxLines: 50, maxEntries: 50 });
  const scope = { workspaceRoot: root };
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }));

  assert.equal((await service.read(scope, "target.txt", {})).text, "fixture text");
  for (const link of ["relative.txt", "absolute.txt"]) {
    assert.equal((await service.read(scope, link, {})).text, "fixture text");
    assert.equal((await service.stat(scope, link)).absolutePath, target);
    assert.deepEqual(Buffer.from((await service.readBytes(scope, link, {})).data), Buffer.from("fixture text\n"));
  }
  assert.deepEqual(Buffer.from((await service.readBytes(scope, "payload.bin", { baseFile: "base-link.txt" })).data), bytes);
  await assert.rejects(service.stat(scope, "broken.txt"), { code: "ENOENT" });
  await assert.rejects(service.stat(scope, "cycle-a"), { code: "ELOOP" });
  await assert.rejects(service.stat(scope, "directory-link"), (error) => error.code === "workspace-file/not-regular-file" && error.details.kind === "directory");
  await assert.rejects(service.list(scope, "outside-directory-link"), { code: "workspace-file/outside-workspace" });
});
