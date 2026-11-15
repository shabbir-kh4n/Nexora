import { Fragment, type ReactNode } from "react";
import { CodeBlock } from "../components/CodeBlock";

// Renders to React elements only (never raw HTML), so model output cannot inject markup or scripts.

type Block =
  | { kind: "paragraph"; text: string }
  | { kind: "heading"; level: number; text: string }
  | { kind: "code"; language: string; code: string }
  | { kind: "list"; ordered: boolean; items: string[] }
  | { kind: "quote"; text: string }
  | { kind: "table"; header: string[]; align: (Alignment | undefined)[]; rows: string[][] }
  | { kind: "rule" };

type Alignment = "left" | "center" | "right";

const FENCE = /^\s*```\s*([\w+#.-]*)\s*$/;
const HEADING = /^(#{1,4})\s+(.+)$/;
const RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;
const BULLET = /^\s*[-*+]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;
const QUOTE = /^>\s?(.*)$/;
// Cells may be momentarily just ":" while the separator row is still streaming in; isTableStart requires a dash and a pipe.
const TABLE_SEPARATOR = /^\s*\|?\s*:?-*:?\s*(\|\s*:?-*:?\s*)*\|?\s*$/;

function splitRow(line: string): string[] {
  const body = line.trim().replace(/^\|/, "").replace(/\|$/, "");
  const cells: string[] = [];
  let cell = "";
  for (let i = 0; i < body.length; i++) {
    const char = body[i];
    if (char === "\\" && body[i + 1] === "|") {
      cell += "|";
      i++;
    } else if (char === "|") {
      cells.push(cell.trim());
      cell = "";
    } else {
      cell += char;
    }
  }
  cells.push(cell.trim());
  return cells;
}

// A header line only becomes a table once its separator line arrives, which is how a streamed table fills in.
// The separator must contain a pipe, otherwise `text | text` above a `---` rule would be read as an empty table.
function isTableStart(lines: string[], i: number): boolean {
  const separator = lines[i + 1] ?? "";
  return (lines[i] ?? "").includes("|") && separator.includes("|") && separator.includes("-") && TABLE_SEPARATOR.test(separator);
}

function alignmentOf(cell: string): Alignment | undefined {
  const left = cell.startsWith(":");
  const right = cell.endsWith(":");
  return left && right ? "center" : right ? "right" : left ? "left" : undefined;
}

function startsBlock(line: string): boolean {
  return (
    FENCE.test(line) || HEADING.test(line) || RULE.test(line) || BULLET.test(line) || NUMBERED.test(line) || QUOTE.test(line)
  );
}

function parseBlocks(source: string): Block[] {
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i] ?? "";
    if (!line.trim()) {
      i++;
      continue;
    }

    const fence = FENCE.exec(line);
    if (fence) {
      const code: string[] = [];
      i++;
      // An unclosed fence is expected while a code block is still streaming.
      while (i < lines.length && !FENCE.test(lines[i] ?? "")) code.push(lines[i++] ?? "");
      i++;
      blocks.push({ kind: "code", language: fence[1] ?? "", code: code.join("\n") });
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      blocks.push({ kind: "heading", level: (heading[1] ?? "#").length, text: heading[2] ?? "" });
      i++;
      continue;
    }

    if (isTableStart(lines, i)) {
      const header = splitRow(line);
      const align = splitRow(lines[i + 1] ?? "").map(alignmentOf);
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && (lines[i] ?? "").trim() && (lines[i] ?? "").includes("|")) rows.push(splitRow(lines[i++] ?? ""));
      blocks.push({ kind: "table", header, align, rows });
      continue;
    }

    if (RULE.test(line)) {
      blocks.push({ kind: "rule" });
      i++;
      continue;
    }

    const listPattern = BULLET.test(line) ? BULLET : NUMBERED.test(line) ? NUMBERED : null;
    if (listPattern) {
      const items: string[] = [];
      for (let match = listPattern.exec(lines[i] ?? ""); match; match = listPattern.exec(lines[i] ?? "")) {
        items.push(match[1] ?? "");
        i++;
      }
      blocks.push({ kind: "list", ordered: listPattern === NUMBERED, items });
      continue;
    }

    if (QUOTE.test(line)) {
      const quoted: string[] = [];
      for (let match = QUOTE.exec(lines[i] ?? ""); match; match = QUOTE.exec(lines[i] ?? "")) {
        quoted.push(match[1] ?? "");
        i++;
      }
      blocks.push({ kind: "quote", text: quoted.join(" ") });
      continue;
    }

    const paragraph: string[] = [];
    while (i < lines.length && (lines[i] ?? "").trim() && !startsBlock(lines[i] ?? "") && !isTableStart(lines, i)) paragraph.push((lines[i++] ?? "").trim());
    blocks.push({ kind: "paragraph", text: paragraph.join(" ") });
  }
  return blocks;
}

// Link URLs may hold one level of parentheses. Groups: 1 code, 2 bold, 3 italic, 4 link.
const INLINE =
  /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\*[^*\s](?:[^*\n]*[^*\s])?\*(?![\w*]))|(\[[^\]\n]+\]\((?:[^()\s]|\([^()\s]*\))+\))/;
const WORD_OR_STAR = /[\w*]/;
const SAFE_URL = /^(https?:\/\/|mailto:)/i;

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  // A fresh pattern per call, because the bold and italic branches recurse and would share lastIndex.
  const pattern = new RegExp(INLINE, "g");
  for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
    const token = match[0];
    const index = match.index;
    // Italic must not start inside a word, so `2*3*4` stays literal. Checked here instead of with a
    // lookbehind, which Safari before 16.4 cannot even parse.
    if (match[3] !== undefined && index > 0 && WORD_OR_STAR.test(text[index - 1] ?? "")) {
      pattern.lastIndex = index + 1;
      continue;
    }
    if (index > last) nodes.push(text.slice(last, index));
    if (token.startsWith("`")) {
      nodes.push(<code key={index}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith("**")) {
      nodes.push(<strong key={index}>{renderInline(token.slice(2, -2))}</strong>);
    } else if (token.startsWith("*")) {
      nodes.push(<em key={index}>{renderInline(token.slice(1, -1))}</em>);
    } else {
      const split = token.indexOf("](");
      const label = token.slice(1, split);
      const url = token.slice(split + 2, -1);
      nodes.push(
        SAFE_URL.test(url) ? (
          <a key={index} href={url} target="_blank" rel="noopener noreferrer">
            {label}
          </a>
        ) : (
          label
        ),
      );
    }
    last = index + token.length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

function renderBlock(block: Block, index: number): ReactNode {
  switch (block.kind) {
    case "paragraph":
      return <p key={index}>{renderInline(block.text)}</p>;
    case "heading": {
      const Tag = (`h${Math.min(block.level + 1, 5)}`) as "h2" | "h3" | "h4" | "h5";
      return <Tag key={index}>{renderInline(block.text)}</Tag>;
    }
    case "code":
      return <CodeBlock key={index} language={block.language} code={block.code} />;
    case "list": {
      const Tag = block.ordered ? "ol" : "ul";
      return (
        <Tag key={index}>
          {block.items.map((item, itemIndex) => (
            <li key={itemIndex}>{renderInline(item)}</li>
          ))}
        </Tag>
      );
    }
    case "table":
      return (
        <div key={index} className="table-wrap">
          <table>
            <thead>
              <tr>
                {block.header.map((cell, column) => (
                  <th key={column} style={{ textAlign: block.align[column] }}>{renderInline(cell)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {block.header.map((_, column) => (
                    <td key={column} style={{ textAlign: block.align[column] }}>{renderInline(row[column] ?? "")}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case "quote":
      return <blockquote key={index}>{renderInline(block.text)}</blockquote>;
    case "rule":
      return <hr key={index} />;
  }
}

export function renderMarkdown(source: string): ReactNode {
  return <Fragment>{parseBlocks(source).map(renderBlock)}</Fragment>;
}
