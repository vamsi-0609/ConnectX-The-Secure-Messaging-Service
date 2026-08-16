import React from 'react';

const URL_PATTERN = /((?:https?:\/\/|www\.)[^\s<>"')\]]+)/gi;

function stripTrailingPunctuation(raw: string): { url: string; trailing: string } {
  const match = raw.match(/[.,!?;:'"]+$/);
  let url = match ? raw.slice(0, raw.length - match[0].length) : raw;
  let trailing = match ? match[0] : '';

  // A trailing ')' only belongs to the punctuation tail if it doesn't balance
  // an earlier '(' in the URL (e.g. a Wikipedia link ending in "(disambiguation)").
  while (url.endsWith(')')) {
    const opens = (url.match(/\(/g) || []).length;
    const closes = (url.match(/\)/g) || []).length;
    if (closes <= opens) break;
    url = url.slice(0, -1);
    trailing = ')' + trailing;
  }

  return { url, trailing };
}

/** Splits message text on URLs and renders them as tappable links, WhatsApp-style. */
export function linkifyText(text: string): React.ReactNode {
  const parts = text.split(URL_PATTERN);
  if (parts.length === 1) return text;

  return parts.map((part, index) => {
    // text.split on a single-capturing-group regex alternates [text, match, text, match, ...]
    if (index % 2 === 0) {
      return part ? <React.Fragment key={index}>{part}</React.Fragment> : null;
    }

    const { url, trailing } = stripTrailingPunctuation(part);
    const href = /^https?:\/\//i.test(url) ? url : `https://${url}`;

    return (
      <React.Fragment key={index}>
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          className="underline decoration-sky-300/50 hover:decoration-sky-300 underline-offset-2 text-sky-300 break-all font-medium transition-colors active:opacity-70"
        >
          {url}
        </a>
        {trailing}
      </React.Fragment>
    );
  });
}
