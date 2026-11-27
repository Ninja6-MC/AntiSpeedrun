#!/usr/bin/env python3
#
# Generates docs/modrinth-description.md from README.md (N6-REL-06).
#
# Usage:
#   python3 scripts/modrinth-description.py            # write the file
#   python3 scripts/modrinth-description.py --check    # fail if the file is out of date
#
# The Modrinth project page lives only on Modrinth, and MODRINTH_TOKEN cannot write it: the
# N6-REL-04 scopes allow reading projects and creating versions, nothing more. So the body is
# derived from the README, committed, checked in CI, and pasted into Modrinth's description
# editor by hand. The README stays the single source of truth; this script removes the parts
# of it that only make sense on github.com and enforces Modrinth's content rules that can be
# checked mechanically. The release workflow then confirms that Modrinth shows the file
# (scripts/release-description.py). See RELEASE_PROCESS.md section 3.

import argparse
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")
OUTPUT = os.path.join(ROOT, "docs", "modrinth-description.md")

# The one place the repository is named. Both URL bases below are built from it.
REPOSITORY = "Ninja6-MC/AntiSpeedrun"

BLOB = "https://github.com/" + REPOSITORY + "/blob/main/"

# An image has to resolve to the file itself. A blob URL serves GitHub's HTML viewer, so
# routing image targets through BLOB would produce a broken image rather than a 404, which
# is harder to notice.
RAW = "https://raw.githubusercontent.com/" + REPOSITORY + "/main/"

# Anything already addressable as-is from a store page. The ONLY definition of absolute
# in this file: the transforms decide what to rewrite by it and the checks decide what to
# reject by it, so a target the transform leaves alone cannot then be rejected, and one it
# rewrites cannot slip past. Divergent copies of this test once meant a mailto link failed
# the build while a protocol-relative one was rewritten to blob/main///host and passed.
# Add a scheme here, not at a call site.
ABSOLUTE = re.compile(r"^(https?:|mailto:|#|data:|//)")

# src/href/srcset in raw HTML, in all three quoting styles. The leading boundary keeps
# data-src and similar from being reported under the wrong attribute name.
ATTRIBUTE = re.compile(
    r"(?:^|[\s<])(src|href|srcset)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s>]+))"
)


# A reference-style link definition, `[label]: target`, optionally with the target in
# angle brackets. The transform only rewrites the form CommonMark always treats as a
# definition - at most three spaces of indentation. The check matches any indentation, so
# a definition the transform declines to touch (inside a list item, say) is rejected
# rather than passed through with its relative target intact.
REFERENCE = re.compile(r"^( {0,3}\[[^\]]+\]:[ \t]*<?)([^\s>]+)")
REFERENCE_ANYWHERE = re.compile(r"^\s*\[[^\]]+\]:[ \t]*<?([^\s>]+)")

# A definition gives no hint whether a link or an image uses it, so the target's
# extension decides which base it is rewritten through.
IMAGE_EXTENSION = re.compile(r"\.(png|jpe?g|gif|svg|webp)([?#]|$)", re.IGNORECASE)


# A fence delimiter: three or more backticks or three or more tildes. CommonMark closes a
# fence only with the same character, at least as long as the opener and followed by
# nothing but whitespace, and a backtick opener cannot carry a backtick in its info string.
# Leading whitespace is accepted at any depth so that a fence inside a list item counts.
FENCE = re.compile(r"^\s*(`{3,}|~{3,})(.*)$")


class ReadmeError(ValueError):
    """The README cannot be transformed at all, as opposed to producing a bad result.

    A distinct type so main() does not also swallow UnicodeDecodeError, which subclasses
    ValueError and would otherwise be reported as a malformed-README error with nothing
    but a codec message to go on.
    """


HEADER = (
    "<!-- Generated from README.md by scripts/modrinth-description.py. Do not edit.\n"
    "     Paste everything below this comment into the Modrinth description editor. -->\n"
)

# Prose characters the README uses that have no business in a store description we want
# to keep diffable and paste-safe. Fenced blocks are deliberately left alone.
ASCII_FOLD = [
    (chr(0x2014), "-"),  # em dash (the README always spaces it)
    (chr(0x2013), "-"),  # en dash
    (chr(0x00b7), "-"),  # middle dot, used as a link separator
    (chr(0x00d7), "x"),  # multiplication sign
    (chr(0x2705), "Yes"),  # check mark
    (chr(0x274c), "No"),  # cross mark
    (chr(0x2b07), ""),  # down arrow, download link
]


def fence_map(lines, source="the generated description"):
    """Pair each line with whether it belongs to a fenced block, delimiters included.

    The ONLY fence test in this file. Every transform and check that must leave code alone
    asks this, so a fence style one of them recognises cannot be prose to another. A
    `~~~` fence once passed a `## key: value` comment as a heading, because only the
    backtick form was known.

    An unclosed fence fails rather than running to the end of the file: everything after
    it would silently stop being checked.
    """
    out = []
    opener = None
    opened = None
    for number, line in enumerate(lines, 1):
        match = FENCE.match(line)
        if opener is None:
            if match and not (match.group(1)[0] == "`" and "`" in match.group(2)):
                opener = match.group(1)
                opened = number
                out.append((line, True))
            else:
                out.append((line, False))
            continue
        if (
            match
            and match.group(1)[0] == opener[0]
            and len(match.group(1)) >= len(opener)
            and match.group(2).strip() == ""
        ):
            opener = None
        out.append((line, True))
    if opener is not None:
        raise ReadmeError(
            "%s line %d opens a code fence (%s) that is never closed." % (source, opened, opener)
        )
    return out


def map_prose(text, transform):
    """Apply transform to each run of consecutive lines outside fenced blocks.

    Runs rather than single lines, so a pattern that spans a line break inside a paragraph
    still matches as it did before fences were skipped.
    """
    out = []
    run = []
    for line, fenced in fence_map(text.split("\n")):
        if fenced:
            if run:
                out.append(transform("\n".join(run)))
                run = []
            out.append(line)
        else:
            run.append(line)
    if run:
        out.append(transform("\n".join(run)))
    return "\n".join(out)


def strip_title(lines):
    """Drop the README's markdown H1.

    Modrinth renders the project name above the description itself. Only the H1
    that opens the file goes; an H1 anywhere else survives and is rejected by
    check_no_stray_hashes as a malformed heading, because it would be a second title.
    """
    for index, line in enumerate(lines):
        if line.strip() == "":
            continue
        if re.match(r"^# \S", line):
            return lines[:index] + lines[index + 1:]
        return lines
    return lines


def strip_html_blocks(lines):
    """Drop the GitHub-only chrome: centred logo, tagline, badge row, footer org mark.

    Modrinth renders the project icon and summary itself, and none of the relative
    image sources resolve there. Anything at the top level that opens an HTML block goes,
    along with everything up to its closing tag.
    """
    out = []
    closing = None
    opened = None
    # Numbered against README.md itself, which is why this runs before strip_title.
    for number, line in enumerate(lines, 1):
        stripped = line.strip()
        if closing is not None:
            if closing in line:
                closing = None
                continue
            # A closing tag is only trusted if it arrives before anything that cannot be
            # part of the block. Otherwise an unclosed block would be closed by a later,
            # unrelated block's tag, and every section in between would be dropped
            # without a word. A heading or a second block opener means the first block
            # was never closed.
            if re.match(r"^#{1,6} ", line) or stripped.startswith("<p ") or stripped.startswith("<p>"):
                raise ReadmeError(
                    "README.md line %d opens an HTML block that is not closed with %s "
                    "before line %d: %s" % (opened, closing, number, stripped)
                )
            continue
        if stripped.startswith("<p ") or stripped.startswith("<p>"):
            if "</p>" not in stripped:
                closing = "</p>"
                opened = number
            continue
        if stripped.startswith("<h1"):
            continue
        out.append(line)

    # An unclosed block would otherwise consume every remaining line and still exit 0,
    # writing a description containing nothing but the generated-by comment. The tool
    # exists to stop a bad page reaching the store, so it must not fail open.
    if closing is not None:
        raise ReadmeError(
            "README.md line %d opens an HTML block that is never closed with %s. "
            "Stripping it would drop the rest of the file." % (opened, closing)
        )

    return out


def strip_store_links(lines):
    """Drop the Download / Modrinth / Hangar row.

    A store page linking to itself is noise, and the download link duplicates the
    versions list Modrinth shows beside the description.
    """
    out = []
    skipping = False
    for line in lines:
        if line.startswith("**[") and "Download]" in line:
            skipping = True
            continue
        if skipping:
            if line.strip() == "":
                skipping = False
            continue
        out.append(line)
    return out


def fold_ascii(lines):
    """Fold typographic characters to ASCII, outside fenced blocks."""
    out = []
    for line, fenced in fence_map(lines):
        if not fenced:
            for bad, good in ASCII_FOLD:
                line = line.replace(bad, good)
        out.append(line)
    return out


def collapse_blanks(lines):
    out = []
    for line in lines:
        if line.strip() == "" and out and out[-1].strip() == "":
            continue
        out.append(line)
    while out and out[0].strip() == "":
        out.pop(0)
    while out and out[-1].strip() in ("", "---"):
        out.pop()
    return out


def check_no_stray_hashes(lines):
    """The rule SpiralGenesis's submission was rejected over.

    Modrinth content rules 2.2: headers separate sections, they are not body text. A
    line-initial "#" inside a fenced block is YAML or shell comment syntax, but read as
    raw markdown it is indistinguishable from a header used as body text - which is how a
    config block got SpiralGenesis rejected on 2026-09-05.
    """
    problems = []
    for number, (line, fenced) in enumerate(fence_map(lines), 1):
        if fenced and FENCE.match(line):
            continue
        if not line.lstrip().startswith("#"):
            continue
        if fenced:
            problems.append("%d: '#' begins a line inside a code fence: %s" % (number, line.strip()))
        elif not re.match(r"^#{2,6} \S", line):
            problems.append("%d: not a well-formed heading: %s" % (number, line.strip()))
    return problems


def check_no_relative_links(text):
    """Fenced blocks are skipped, matching absolutise_links, which leaves them alone."""
    problems = []
    for number, (line, fenced) in enumerate(fence_map(text.split("\n")), 1):
        if fenced:
            continue
        for match in re.finditer(r"\]\(([^)]+)\)", line):
            target = match.group(1)
            if not ABSOLUTE.match(target):
                problems.append("%d: relative link: %s" % (number, target))
    return problems


def check_no_relative_reference_definitions(text):
    """Reject reference-style definitions whose target is still relative.

    `[guide]: docs/ADMIN_GUIDE.md` is as much a link as `[guide](docs/ADMIN_GUIDE.md)`,
    but the inline link check cannot see it. Fenced blocks are skipped: there the line is
    code, not a definition.
    """
    problems = []
    for number, (line, fenced) in enumerate(fence_map(text.split("\n")), 1):
        if fenced:
            continue
        match = REFERENCE_ANYWHERE.match(line)
        if match and not ABSOLUTE.match(match.group(1)):
            problems.append("%d: relative reference definition: %s" % (number, match.group(1)))
    return problems


def check_no_relative_html_refs(text):
    """Catch relative references the stripper does not know how to remove.

    strip_html_blocks only recognises the chrome this README actually uses, so a tag it
    has never seen - a <div> wrapper, a bare <img> - survives into the output with its
    src or href intact. A relative one 404s on a store page exactly like a relative
    markdown link does, and the markdown link check cannot see it because it is not
    markdown. So assert on the output rather than trying to enumerate every tag.

    All three quoting styles are matched. Checking only double quotes would close the
    common case and leave the general one open, which is the kind of half-fix that reads
    as covered in review.
    """
    problems = []
    for number, (line, fenced) in enumerate(fence_map(text.split("\n")), 1):
        if fenced:
            continue
        for attribute, double, single, bare in ATTRIBUTE.findall(line):
            value = double or single or bare
            # srcset carries a comma-separated candidate list, each entry a URL
            # followed by an optional descriptor. Every candidate has to resolve, so
            # checking the whole value as one URL would pass on the first entry alone.
            for candidate in value.split(",") if attribute == "srcset" else [value]:
                target = candidate.strip().split(" ")[0]
                if target and not ABSOLUTE.match(target):
                    problems.append(
                        "%d: relative %s in raw HTML: %s" % (number, attribute, target)
                    )
    return problems


def check_body_is_intact(text):
    """A last sanity check that something actually survived the transforms.

    Cheap insurance against a stripper bug quietly producing an empty page. The README
    has several sections, so an output with none of them means a transform went wrong,
    not that the README got shorter.
    """
    if not re.search(r"(?m)^## \S", text):
        return ["generated description contains no section headings at all"]
    return []


def check_ascii(text):
    problems = []
    for number, (line, fenced) in enumerate(fence_map(text.split("\n")), 1):
        if fenced:
            continue
        for character in line:
            if ord(character) > 127:
                problems.append("%d: non-ASCII U+%04X outside a code fence" % (number, ord(character)))
                break
    return problems


def absolutise_image_targets(text):
    """Point relative markdown image targets at raw.githubusercontent.com.

    Runs before absolutise_links, which would otherwise rewrite them through BLOB and
    produce an image tag pointing at an HTML page. The README carries no markdown images
    today; this exists so that adding one does not quietly break the store page. Fenced
    blocks are left alone: there the target is example code, not a link.
    """

    def replace(match):
        target = match.group(2)
        if ABSOLUTE.match(target):
            return match.group(0)
        return "!" + match.group(1) + "(" + RAW + target + ")"

    return map_prose(text, lambda prose: re.sub(r"!(\[[^\]]*\])\(([^)]+)\)", replace, prose))


def absolutise_links(text):
    """Point relative markdown links at github.com.

    On a store page a relative target resolves against the project URL and 404s. Fenced
    blocks are left alone, as in absolutise_image_targets.
    """

    def replace(match):
        target = match.group(1)
        if ABSOLUTE.match(target):
            return match.group(0)
        return "](" + BLOB + target + ")"

    return map_prose(text, lambda prose: re.sub(r"\]\(([^)]+)\)", replace, prose))


def absolutise_reference_definitions(text):
    """Point relative reference-style definitions at github.com, outside fenced blocks.

    Image files go through RAW and everything else through BLOB, for the same reason
    absolutise_image_targets runs before absolutise_links.
    """
    out = []
    for line, fenced in fence_map(text.split("\n")):
        if not fenced:
            match = REFERENCE.match(line)
            if match and not ABSOLUTE.match(match.group(2)):
                base = RAW if IMAGE_EXTENSION.search(match.group(2)) else BLOB
                line = match.group(1) + base + line[match.start(2):]
        out.append(line)
    return "\n".join(out)


def render():
    with io.open(README, encoding="utf-8") as handle:
        lines = handle.read().replace("\r\n", "\n").split("\n")

    # Checked against the README first, so an unclosed fence is reported by its README
    # line number rather than by a line of the partly transformed output.
    fence_map(lines, "README.md")

    lines = strip_html_blocks(lines)
    lines = strip_title(lines)
    lines = strip_store_links(lines)
    lines = fold_ascii(lines)
    lines = collapse_blanks(lines)

    text = absolutise_links(absolutise_image_targets("\n".join(lines)))
    text = absolutise_reference_definitions(text) + "\n"

    problems = (
        check_no_stray_hashes(text.split("\n"))
        + check_no_relative_links(text)
        + check_no_relative_reference_definitions(text)
        + check_no_relative_html_refs(text)
        + check_ascii(text)
        + check_body_is_intact(text)
    )
    if problems:
        sys.stderr.write("Generated description violates the Modrinth content rules:\n")
        for problem in problems:
            sys.stderr.write("  " + problem + "\n")
        sys.exit(1)

    return HEADER + "\n" + text


def main():
    parser = argparse.ArgumentParser(
        description="Generate docs/modrinth-description.md from README.md."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if docs/modrinth-description.md is out of date",
    )
    args = parser.parse_args()

    # A malformed README is a content error, not a crash: report it the same way a
    # rule violation is reported so CI logs read the same for both.
    try:
        generated = render()
    except ReadmeError as error:
        sys.stderr.write("Cannot generate the description from README.md:\n")
        sys.stderr.write("  " + str(error) + "\n")
        sys.exit(1)

    if args.check:
        if not os.path.exists(OUTPUT):
            sys.stderr.write(
                "docs/modrinth-description.md does not exist.\n"
                "Run: python3 scripts/modrinth-description.py\n"
            )
            sys.exit(1)
        with io.open(OUTPUT, encoding="utf-8") as handle:
            current = handle.read().replace("\r\n", "\n")
        if current != generated:
            sys.stderr.write(
                "docs/modrinth-description.md is out of date with README.md.\n"
                "Run: python3 scripts/modrinth-description.py\n"
            )
            sys.exit(1)
        print("docs/modrinth-description.md is up to date.")
        return

    with io.open(OUTPUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(generated)
    print("Wrote docs/modrinth-description.md (%d characters)." % len(generated))


if __name__ == "__main__":
    main()
