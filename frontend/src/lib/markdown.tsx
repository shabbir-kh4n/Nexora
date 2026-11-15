import { Fragment, type ReactNode } from "react";
import { CodeBlock } from "../components/CodeBlock";

// Renders to React elements only (never raw HTML), so model output cannot inject markup or scripts.

type Block =
  | { kind: "paragraph"; text: string }
  | { kind: "heading"; level: number; text: string }
  | { kind: "code"; language: string; code: string }
  | { kind: "list"; ordered: boolean; items: string[] }
  | { kind: "quote"; text: string }
  | { kind: "rule" };

const FENCE = /^\s*```\s*([\w+#.-]*)\s*$/;
const HEADING = /^(#{1,4})\s+(.+)$/;
const RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;
const BULLET = /^\s*[-*+]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;
const QUOTE = /^>\s?(.*)$/;

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
    while (i < lines.length && (lines[i] ?? "").trim() && !startsBlock(lines[i] ?? "")) paragraph.push((lines[i++] ?? "").trim());
    blocks.push({ kind: "paragraph", text: paragraph.join(" ") });
  }
  return blocks;
}

const INLINE = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\*[^*\s][^*\n]*\*)|(\[[^\]\n]+\]\([^)\s]+\))/g;
const SAFE_URL = /^(https?:\/\/|mailto:)/i;

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  for (const match of text.matchAll(INLINE)) {
    const token = match[0];
    const index = match.index;
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
    case "quote":
      return <blockquote key={index}>{renderInline(block.text)}</blockquote>;
    case "rule":
      return <hr key={index} />;
  }
}

export function renderMarkdown(source: string): ReactNode {
  return <Fragment>{parseBlocks(source).map(renderBlock)}</Fragment>;
}
