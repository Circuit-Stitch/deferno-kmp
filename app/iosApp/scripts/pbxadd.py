#!/usr/bin/env python3
"""Register Swift source files into the hand-authored iosApp.xcodeproj/project.pbxproj.

Idempotent: skips files already referenced. Follows the project's synthetic ID scheme
(DE0001…=fileRef, DE0002…=buildFile, DE0003…=group), allocating new suffixes from 100 up.

Usage:  pbxadd.py Group=File.swift [Group/Sub=File.swift ...]
        Group is the PBXGroup display name (created under the iosApp group if missing).
"""
import re, sys, pathlib

PROJ = pathlib.Path(__file__).resolve().parent.parent / "iosApp.xcodeproj" / "project.pbxproj"
APP_GROUP_ID = "DE0003000000000000000002"   # the /* iosApp */ group
SOURCES_PHASE_ID = "DE0004000000000000000002"  # the app target Sources build phase

def fid(kind, n):  # 24-char synthetic id: DE000<kind> + zero-padded number
    return f"DE000{kind}" + str(n).zfill(18)

def main():
    entries = []
    for arg in sys.argv[1:]:
        group, _, fname = arg.partition("=")
        entries.append((group.strip(), fname.strip()))
    text = PROJ.read_text()

    # Next free numeric suffix across all DE000x ids.
    used = [int(m) for m in re.findall(r"DE000\d(\d{18})", text)]
    nxt = max([n for n in used] + [99]) + 1

    # Map existing group display-name -> group id.
    group_ids = {}
    for gid, name in re.findall(r"(DE0003\d{18}) /\* ([^*]+?) \*/ = \{\s*isa = PBXGroup;", text):
        group_ids[name.strip()] = gid

    new_buildfiles, new_filerefs, new_groups = [], [], []
    group_children = {}   # group_id -> [child ref lines]
    sources_children = []
    app_group_children = []

    for group, fname in entries:
        if re.search(re.escape(f"/* {fname} */"), text):
            print(f"skip (already present): {fname}")
            continue
        fref = fid(1, nxt); bfile = fid(2, nxt); nxt += 1
        new_filerefs.append(
            f'\t\t{fref} /* {fname} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {fname}; sourceTree = "<group>"; }};')
        new_buildfiles.append(
            f'\t\t{bfile} /* {fname} in Sources */ = {{isa = PBXBuildFile; fileRef = {fref} /* {fname} */; }};')
        sources_children.append(f'\t\t\t\t{bfile} /* {fname} in Sources */,')

        if group not in group_ids:
            gid = fid(3, nxt); nxt += 1
            group_ids[group] = gid
            new_groups.append(
                f'\t\t{gid} /* {group} */ = {{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n'
                f'GROUPKIDS:{gid}\n\t\t\t);\n\t\t\tpath = {group};\n\t\t\tsourceTree = "<group>";\n\t\t}};')
            app_group_children.append(f'\t\t\t\t{gid} /* {group} */,')
        group_children.setdefault(group_ids[group], []).append(
            f'\t\t\t\t{fref} /* {fname} */,')

    if not new_filerefs:
        print("nothing to add."); return

    # Insert build files + file refs before their section ends.
    text = text.replace("/* End PBXBuildFile section */",
                        "\n".join(new_buildfiles) + "\n/* End PBXBuildFile section */")
    text = text.replace("/* End PBXFileReference section */",
                        "\n".join(new_filerefs) + "\n/* End PBXFileReference section */")

    # New groups: create block (with their own children) before section end.
    for blk in new_groups:
        gid = blk.split(" /*", 1)[0].strip()
        kids = "\n".join(group_children.get(gid, []))
        blk = blk.replace(f"GROUPKIDS:{gid}", kids)
        text = text.replace("/* End PBXGroup section */", blk + "\n/* End PBXGroup section */")

    # Existing groups: append children right after their `children = (`.
    for group, gid in group_ids.items():
        kids = group_children.get(gid)
        if not kids:
            continue
        if any(gid in g for g in new_groups):  # already populated at creation
            continue
        pat = re.compile(r"(" + re.escape(gid) + r" /\* [^*]+ \*/ = \{\s*isa = PBXGroup;\s*children = \(\n)")
        text, n = pat.subn(lambda m: m.group(1) + "\n".join(kids) + "\n", text)
        assert n == 1, f"could not find group {group} ({gid})"

    # New groups: add to the iosApp group children.
    if app_group_children:
        pat = re.compile(r"(" + re.escape(APP_GROUP_ID) + r" /\* iosApp \*/ = \{\s*isa = PBXGroup;\s*children = \(\n)")
        text, n = pat.subn(lambda m: m.group(1) + "\n".join(app_group_children) + "\n", text)
        assert n == 1, "could not find iosApp group"

    # Sources build phase: add the build-file children.
    pat = re.compile(r"(" + re.escape(SOURCES_PHASE_ID) + r" /\* Sources \*/ = \{\s*isa = PBXSourcesBuildPhase;\s*buildActionMask = \d+;\s*files = \(\n)")
    text, n = pat.subn(lambda m: m.group(1) + "\n".join(sources_children) + "\n", text)
    assert n == 1, "could not find app Sources phase"

    PROJ.write_text(text)
    print(f"added {len(new_filerefs)} file(s).")

if __name__ == "__main__":
    main()
