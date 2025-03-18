(function ( maestro ) {
    const INVALID_TAGS = new Set(['noscript', 'script', 'br', 'img', 'svg', 'g', 'path', 'style'])

    const isInvalidTag = (node) => {
        return INVALID_TAGS.has(node.tagName.toLowerCase())
    }

    const getNodeText = (node) => {
        switch (node.tagName.toLowerCase()) {
            case 'input':
                return node.value || node.placeholder || node.ariaLabel || ''

            default:
                const childNodes = [...(node.childNodes || [])].filter(node => node.nodeType === Node.TEXT_NODE)
                return childNodes.map(node => node.textContent.replace('\n', '').replace('\t', '')).join('')
        }
    }

    const getNodeBounds = (node) => {
        const rect = node.getBoundingClientRect()
        const vpx = maestro.viewportX;
        const vpy = maestro.viewportY;
        const vpw = maestro.viewportWidth || window.innerWidth;
        const vph = maestro.viewportHeight || window.innerHeight;

        const scaleX = vpw / window.innerWidth;
        const scaleY = vph / window.innerHeight;
        const l = rect.x * scaleX + vpx;
        const t = rect.y * scaleY + vpy;
        const r = (rect.x + rect.width) * scaleX + vpx;
        const b = (rect.y + rect.height) * scaleY + vpy;

        return `[${Math.round(l)},${Math.round(t)}][${Math.round(r)},${Math.round(b)}]`
    }

    const isDocumentLoading = () => document.readyState !== 'complete'

    const traverse = (node) => {
      if (!node || isInvalidTag(node)) return null

      const children = [...node.children || []].map(child => traverse(child)).filter(el => !!el)
      const attributes = {
          text: getNodeText(node),
          bounds: getNodeBounds(node),
      }

      if (!!node.id || !!node.ariaLabel || !!node.name || !!node.title || !!node.htmlFor || !!node.attributes['data-testid']) {
        const title = typeof node.title === 'string' ? node.title : null
        attributes['resource-id'] = node.id || node.ariaLabel || node.name || title || node.htmlFor || node.attributes['data-testid']?.value
      }

      if (node.tagName.toLowerCase() === 'body') {
        attributes['is-loading'] = isDocumentLoading()
      }

      return {
        attributes,
        children,
      }
    }

    // -------------- Public API --------------
    maestro.viewportX = 0;
    maestro.viewportY = 0;
    maestro.viewportWidth = 0;
    maestro.viewportHeight = 0;

    maestro.getContentDescription = () => {
        return traverse(document.body)
    }

    // https://stackoverflow.com/a/5178132
    maestro.createXPathFromElement = (domElement) => {
        var allNodes = document.getElementsByTagName('*');
        for (var segs = []; domElement && domElement.nodeType == 1; domElement = domElement.parentNode)
        {
            if (domElement.hasAttribute('id')) {
                    var uniqueIdCount = 0;
                    for (var n=0;n < allNodes.length;n++) {
                        if (allNodes[n].hasAttribute('id') && allNodes[n].id == domElement.id) uniqueIdCount++;
                        if (uniqueIdCount > 1) break;
                    }
                    if ( uniqueIdCount == 1) {
                        segs.unshift('id("' + domElement.getAttribute('id') + '")');
                        return segs.join('/');
                    } else {
                        segs.unshift(domElement.localName.toLowerCase() + '[@id="' + domElement.getAttribute('id') + '"]');
                    }
            } else if (domElement.hasAttribute('class')) {
                segs.unshift(domElement.localName.toLowerCase() + '[@class="' + domElement.getAttribute('class') + '"]');
            } else {
                for (i = 1, sib = domElement.previousSibling; sib; sib = sib.previousSibling) {
                    if (sib.localName == domElement.localName)  i++; }
                    segs.unshift(domElement.localName.toLowerCase() + '[' + i + ']');
            }
        }
        return segs.length ? '/' + segs.join('/') : null;
    }
}( window.maestro = window.maestro || {} ));
