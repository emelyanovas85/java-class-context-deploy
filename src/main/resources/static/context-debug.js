(function () {
  'use strict';

  const ROWS_WIDTH = 10;
  const INDENT_SIZE = 4;
  const KEY_PIN = 'ctx-index-pinned';

  const dataEl = document.getElementById('ctx-data');
  if (!dataEl) return;

  const DATA = JSON.parse(dataEl.textContent);
  const classes = DATA.classes || [];

  const allQualifiedNames = collectAllQualifiedNames(classes);
  const contextIdByName = buildContextIdByName(classes);
  const nestedToRootTop = buildNestedToRootTopLevel(classes);
  const highlightPatterns = buildHighlightPatterns(
      allQualifiedNames, contextIdByName, nestedToRootTop);

  document.getElementById('type-count').textContent = String(allQualifiedNames.size);
  renderMeta(DATA);
  renderIndex(classes);
  renderSections(classes, highlightPatterns);
  initPinLayout();

  function initPinLayout() {
    const cb = document.getElementById('pin-index');
    const layout = document.getElementById('layout-body');
    if (!cb || !layout) return;
    const saved = localStorage.getItem(KEY_PIN);
    if (saved !== null) cb.checked = saved === '1';
    const apply = () => {
      layout.classList.toggle('pinned', cb.checked);
      localStorage.setItem(KEY_PIN, cb.checked ? '1' : '0');
    };
    cb.addEventListener('change', apply);
    apply();
  }

  function renderMeta(data) {
    const el = document.getElementById('page-meta');
    const mr = data.mergeRequest;
    let html = '';
    if (mr) {
      html += 'MR !' + escapeHtml(String(mr.iid)) + ' — ' + escapeHtml(mr.title || '');
      html += '<br>source: <code>' + escapeHtml(mr.sourceBranch || '') + '</code>';
      html += ' → target: <code>' + escapeHtml(mr.targetBranch || '') + '</code><br>';
    }
    html += 'depth=' + (data.requestedDepth ?? '') + ', classes=' + (data.totalClassesAnalyzed ?? '');
    el.innerHTML = html;
  }

  function renderIndex(classes) {
    const nav = document.getElementById('ctx-index');
    if (!classes.length) {
      nav.innerHTML = '';
      return;
    }
    let html = '<h2>Оглавление (' + classes.length + ')</h2><ol>';
    classes.forEach((ctx, i) => {
      html += '<li>' + classLinkHtml(sectionId(i + 1), ctx) + '</li>';
    });
    html += '</ol>';
    nav.innerHTML = html;
  }

  function renderSections(classes, patterns) {
    const main = document.getElementById('ctx-sections');
    let html = '';
    classes.forEach((ctx, i) => {
      const index = i + 1;
      const sid = sectionId(index);
      const body = highlight(escapeHtml(formatClassBody(ctx)), patterns);
      html += '<section class="ctx-block" id="' + sid + '"><h2>';
      html += '<span class="section-idx">#' + index + '</span>';
      html += classLinkHtml(sid, ctx);
      html += '</h2><pre class="ctx">' + body + '</pre></section>';
    });
    main.innerHTML = html;
  }

  function classLinkHtml(sectionId, ctx) {
    return '<a href="#' + sectionId + '">' + escapeHtml(ctx.name) + '</a>'
        + ' <span class="ctx-meta">' + escapeHtml(formatCtxMeta(ctx)) + '</span>';
  }

  function formatCtxMeta(ctx) {
    const callers = ctx.callerIds ? [...ctx.callerIds].join(', ') : '';
    return '[level=' + ctx.level + ', id=' + ctx.id + ', callers=[' + callers + '], module=' + ctx.module + ']';
  }

  function formatClassBody(ctx) {
    if (ctx.kind === 'unchanged' || (ctx.structure && !ctx.structureSource)) {
      return renderStructure(ctx.structure || [], 0);
    }
    let sb = '';
    if (ctx.structureSource) {
      sb += '#### [branch=source]\n';
      sb += renderStructure(ctx.structureSource, 0) + '\n';
    }
    if (ctx.structureTarget) {
      sb += '#### [branch=target]\n';
      sb += renderStructure(ctx.structureTarget, 0);
    }
    return sb;
  }

  function sectionId(index) {
    return 'ctx-' + index;
  }

  function collectAllQualifiedNames(classes) {
    const qnames = new Set();
    for (const ctx of classes) {
      qnames.add(ctx.name);
      for (const roots of structureRoots(ctx)) {
        collectNestedFromStructure(roots, ctx.name, qnames);
      }
    }
    return qnames;
  }

  function buildContextIdByName(classes) {
    const map = new Map();
    for (const ctx of classes) {
      map.set(ctx.name, ctx.id);
    }
    return map;
  }

  /** nested qualified name → top-level outer qualified name (контекст файла). */
  function buildNestedToRootTopLevel(classes) {
    const map = new Map();
    for (const ctx of classes) {
      for (const roots of structureRoots(ctx)) {
        mapNestedToRootTop(roots, ctx.name, map);
      }
    }
    return map;
  }

  function mapNestedToRootTop(nodes, rootTopQn, map) {
    if (!nodes) return;
    for (const node of nodes) {
      if (isTypeNode(node)) {
        const simple = simpleNameFromSignature(node.signature, node.type);
        const isOuterShell = simple && simple === simpleName(rootTopQn);
        if (simple && !isOuterShell) {
          const nestedQn = rootTopQn + '.' + simple;
          map.set(nestedQn, rootTopQn);
          if (node.children) mapNestedToRootTop(node.children, rootTopQn, map);
        } else if (node.children) {
          mapNestedToRootTop(node.children, rootTopQn, map);
        }
      } else if (node.children) {
        mapNestedToRootTop(node.children, rootTopQn, map);
      }
    }
  }

  /** id контекста: для nested всегда id top-level outer, иначе свой. */
  function resolveContextId(qualifiedName, contextIdByName, nestedToRootTop) {
    const root = nestedToRootTop.get(qualifiedName);
    if (root && contextIdByName.has(root)) {
      return contextIdByName.get(root);
    }
    if (contextIdByName.has(qualifiedName)) {
      return contextIdByName.get(qualifiedName);
    }
    return undefined;
  }

  function structureRoots(ctx) {
    if (ctx.kind === 'unchanged' || (ctx.structure && !ctx.structureSource)) {
      return ctx.structure ? [ctx.structure] : [];
    }
    const roots = [];
    if (ctx.structureSource) roots.push(ctx.structureSource);
    if (ctx.structureTarget) roots.push(ctx.structureTarget);
    return roots;
  }

  function collectNestedFromStructure(nodes, enclosingQn, qnames) {
    if (!nodes) return;
    for (const node of nodes) {
      if (isTypeNode(node)) {
        const simple = simpleNameFromSignature(node.signature, node.type);
        const isOuterShell = simple && simple === simpleName(enclosingQn);
        if (simple && !isOuterShell) {
          const nestedQn = enclosingQn + '.' + simple;
          qnames.add(nestedQn);
          if (node.children) collectNestedFromStructure(node.children, nestedQn, qnames);
        } else if (node.children) {
          collectNestedFromStructure(node.children, enclosingQn, qnames);
        }
      } else if (node.children) {
        collectNestedFromStructure(node.children, enclosingQn, qnames);
      }
    }
  }

  function isTypeNode(node) {
    return ['class', 'interface', 'enum', 'record', 'annotation'].includes(node.type);
  }

  function simpleNameFromSignature(signature, kind) {
    if (!signature || !signature.trim()) return null;
    const keywords = { class: 'class', interface: 'interface', record: 'record', enum: 'enum', annotation: '@interface' };
    const keyword = keywords[kind];
    if (keyword) {
      const idx = signature.indexOf(keyword);
      if (idx >= 0) {
        const rest = signature.substring(idx + keyword.length).trim();
        let name = '';
        for (let i = 0; i < rest.length; i++) {
          const c = rest.charAt(i);
          if (/[\w\u0400-\u04FF$]/.test(c)) name += c;
          else break;
        }
        if (name) return name;
      }
    }
    const parts = signature.trim().split(/\s+/);
    return parts.length ? parts[parts.length - 1] : null;
  }

  function buildHighlightPatterns(qualifiedNames, contextIdByName, nestedToRootTop) {
    if (!qualifiedNames.size) return [];
    const simpleCounts = new Map();
    for (const qn of qualifiedNames) {
      const s = simpleName(qn);
      simpleCounts.set(s, (simpleCounts.get(s) || 0) + 1);
    }
    const textToQn = new Map();
    for (const qn of qualifiedNames) {
      putPattern(textToQn, qn, qn);
      const two = twoSegmentSuffix(qn);
      if (two) putPattern(textToQn, two, qn);
      const simple = simpleName(qn);
      if (simpleCounts.get(simple) === 1) putPattern(textToQn, simple, qn);
    }
    return [...textToQn.entries()]
        .sort((a, b) => b[0].length - a[0].length)
        .map(([text, qualifiedName]) => ({
          text,
          qualifiedName,
          contextId: resolveContextId(qualifiedName, contextIdByName, nestedToRootTop),
        }));
  }

  function formatMarkTitle(pattern) {
    const qn = pattern.qualifiedName;
    const id = pattern.contextId;
    if (id !== undefined && id !== null) {
      return escapeHtml(qn + ' (id=' + id + ')');
    }
    return escapeHtml(qn);
  }

  function putPattern(map, text, qn) {
    if (!map.has(text)) map.set(text, qn);
  }

  function isTypeTokenAt(text, start, end) {
    const leftOk = start === 0 || isLeftTypeBoundary(text.charAt(start - 1));
    const rightOk = end >= text.length || isRightTypeBoundary(text.charAt(end));
    return leftOk && rightOk;
  }

  function isLeftTypeBoundary(c) {
    return c === '@' || c === ',' || c === '<' || c === '(' || c === '[' || /\s/.test(c);
  }

  function isRightTypeBoundary(c) {
    return c === '.' || c === '>' || c === ',' || c === ')' || c === '(' || c === ']'
        || c === ';' || c === '[' || c === ':' || /\s/.test(c);
  }

  function highlight(escapedText, patterns) {
    if (!patterns.length || !escapedText) return escapedText;
    let out = '';
    let pos = 0;
    while (pos < escapedText.length) {
      let best = null;
      for (const p of patterns) {
        const end = pos + p.text.length;
        if (escapedText.startsWith(p.text, pos) && isTypeTokenAt(escapedText, pos, end)) {
          if (!best || p.text.length > best.text.length) best = p;
        }
      }
      if (!best) {
        out += escapedText.charAt(pos);
        pos++;
      } else {
        out += '<mark class="ctx-type" title="' + formatMarkTitle(best) + '">';
        out += best.text;
        out += '</mark>';
        pos += best.text.length;
      }
    }
    return out;
  }

  function simpleName(qn) {
    const dot = qn.lastIndexOf('.');
    return dot < 0 ? qn : qn.substring(dot + 1);
  }

  function twoSegmentSuffix(qn) {
    const last = qn.lastIndexOf('.');
    if (last <= 0) return null;
    const prev = qn.lastIndexOf('.', last - 1);
    if (prev < 0) return null;
    return qn.substring(prev + 1);
  }

  function escapeHtml(text) {
    if (!text) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
  }

  // ── StructureNodePrinter (порт) ─────────────────────────────────────────

  function renderStructure(nodes, depth) {
    if (!nodes || !nodes.length) return '';
    let sb = '';
    for (const node of sortedNodes(nodes)) {
      sb += renderNode(node, depth);
    }
    return sb;
  }

  function renderNode(node, depth) {
    const indent = ' '.repeat(INDENT_SIZE * depth);
    const sigLines = splitSignature(node.signature);
    const range = parseRange(node.rows);
    let sb = '';

    for (let i = 0; i < sigLines.length; i++) {
      let cell;
      if (!range) {
        cell = i === 0 ? formatRows(node.rows) : ' '.repeat(ROWS_WIDTH);
      } else {
        const lineNo = range[0] + i;
        if (i === sigLines.length - 1) {
          cell = formatRows(lineNo === range[1] ? String(lineNo) : lineNo + '-' + range[1]);
        } else {
          cell = formatRows(String(lineNo));
        }
      }
      sb += '|' + cell + '|' + indent + sigLines[i] + '\n';
    }

    if (node.children) {
      for (const child of sortedNodes(node.children)) {
        sb += renderNode(child, depth + 1);
      }
    }
    return sb;
  }

  function parseRange(rows) {
    if (!rows || !rows.trim()) return null;
    try {
      const dash = rows.indexOf('-');
      if (dash < 0) {
        const v = parseInt(rows.trim(), 10);
        return [v, v];
      }
      const start = parseInt(rows.substring(0, dash).trim(), 10);
      const end = parseInt(rows.substring(dash + 1).trim(), 10);
      return [start, end];
    } catch (e) {
      return null;
    }
  }

  function sortedNodes(nodes) {
    return [...nodes].sort((a, b) => startLine(a) - startLine(b));
  }

  function startLine(node) {
    const range = parseRange(node.rows);
    return range ? range[0] : Number.MAX_SAFE_INTEGER;
  }

  function formatRows(rows) {
    if (!rows || !rows.trim()) return ' '.repeat(ROWS_WIDTH);
    if (rows.length >= ROWS_WIDTH) return rows.substring(0, ROWS_WIDTH);
    return rows.padStart(ROWS_WIDTH);
  }

  function splitSignature(signature) {
    if (!signature || !signature.trim()) return [''];
    return signature.split('\n').map(l => l.trim()).filter(l => l.length > 0);
  }
})();
