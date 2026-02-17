(function ( maestro ) {
    const INVALID_TAGS = new Set(['noscript', 'script', 'br', 'img', 'svg', 'g', 'path', 'style'])

    const isInvalidTag = (node) => {
        return INVALID_TAGS.has(node.tagName.toLowerCase())
    }

    // Synthetic nodes do not truly have a visual representation in the DOM, but they are still visible to the user.
    const isSynthetic = (node) => {
        return node.tagName.toLowerCase() === 'option'
    }

    const getNodeText = (node) => {
        switch (node.tagName.toLowerCase()) {
            case 'input':
                return node.value || node.placeholder || node.ariaLabel || ''

            case 'select':
                return Array.from(node.selectedOptions).map((option) => option.text).join(', ')

            default:
                const childNodes = [...(node.childNodes || [])].filter(node => node.nodeType === Node.TEXT_NODE)
                return childNodes.map(node => node.textContent.replace('\n', '').replace('\t', '')).join('')
        }
    }

    const getIndexInParent = (node) => {
        if (!node.parentElement) return -1;

        const siblings = Array.from(node.parentElement.children);
        return siblings.indexOf(node);
    }

    const getSyntheticNodeBounds = (node) => {
        // If the node is synthetic, we return bounds in a special coordinate space that doesn't interfere
        // with the rest of the DOM. We do this by adding 100000 offset to the x and y coordinates.

        const idx = getIndexInParent(node);

        const width = 100;
        const height = 20;

        const offset = 100000;

        const x = offset;
        const y = offset + (idx * height);

        const l = x;
        const t = y;
        const r = x + width;
        const b = y + height;

        return `[${Math.round(l)},${Math.round(t)}][${Math.round(r)},${Math.round(b)}]`
    }

    const getNodeBounds = (node) => {
        if (isSynthetic(node)) {
            return getSyntheticNodeBounds(node);
        }

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

    const traverse = (node, includeChildren = true) => {
      if (!node || isInvalidTag(node)) return null

      const children = includeChildren
        ? [...node.children || []].map(child => traverse(child)).filter(el => !!el)
        : []

      const attributes = {
          text: getNodeText(node),
          bounds: getNodeBounds(node),
      }

      // If this is an <option> element, we only want to include it if the parent <select> element is focused.
      if (node.tagName.toLowerCase() === 'option' && !node.parentElement.matches(':focus-within')) {
        return null;
      }

      if (!!node.id || !!node.ariaLabel || !!node.name || !!node.title || !!node.htmlFor || !!node.attributes['data-testid']) {
        const title = typeof node.title === 'string' ? node.title : null
        attributes['resource-id'] = node.id || node.ariaLabel || node.name || title || node.htmlFor || node.attributes['data-testid']?.value
      }

      if (node.tagName.toLowerCase() === 'body') {
        attributes['is-loading'] = isDocumentLoading()
      }

      if (node.selected) {
        attributes['selected'] = true
      }

      if (isSynthetic(node)) {
        attributes['synthetic'] = true
        attributes['ignoreBoundsFiltering'] = true
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

    maestro.queryCss = (selector) => {
        // Returns a list of matching elements for the given CSS selector.
        // Does not include children of discovered elements.
        const elements = document.querySelectorAll(selector);

        return Array.from(elements).map(el => {
            return traverse(el, false);
        });
    }

    maestro.tapOnSyntheticElement = (x, y) => {
        // This function is used to tap on synthetic elements like <option> that do not have a visual representation.
        // It will return the bounds of the synthetic element in a special coordinate space.

        const syntheticElements = Array.from(document.querySelectorAll('option'));
        if (syntheticElements.length === 0) {
            throw new Error('No synthetic elements found');
        }

        for (const option of syntheticElements) {
            const bounds = getSyntheticNodeBounds(option);
            const [left, top] = bounds.match(/\d+/g).map(Number);
            const [right, bottom] = bounds.match(/\d+/g).slice(2).map(Number);

            if (x >= left && x <= right && y >= top && y <= bottom) {
                const select = option.parentElement;
                option.selected = true;

                // Without this, browser will not update the select element's value.
                select.dispatchEvent(new Event("change", { bubbles: true }));

                // This is needed to hide the <select> dropdown after selection.
                select.blur();

                return;
            }
        }
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

    // -------------- Flutter Web Scrolling Support --------------
    
    maestro.isFlutterApp = () => {
        // Detect if this is a Flutter web app by checking for Flutter-specific elements
        const flutterView = document.querySelector('flutter-view');
        const glassPane = document.querySelector('flt-glass-pane');
        const fltRenderer = document.querySelector('[flt-renderer]');
        
        const isFlutter = !!(flutterView || glassPane || fltRenderer);
        
        return isFlutter;
    }

    maestro.smoothScrollFlutterByDelta = (totalDeltaX, totalDeltaY, durationMs = 500) => {
        // Core smooth animated scrolling for Flutter web using explicit delta values
        return new Promise((resolve) => {
            const target = document.querySelector('flutter-view') || 
                          document.querySelector('flt-glass-pane');
            
            if (!target) {
                console.error('[Maestro] Flutter root element not found');
                resolve(false);
                return;
            }

            const duration = typeof durationMs === 'number' && durationMs > 0 ? durationMs : 500;
            const x = window.innerWidth / 2;
            const y = window.innerHeight / 2;
            const start = performance.now();
            let lastX = 0;
            let lastY = 0;
            
            function animate(now) {
                const progress = Math.min((now - start) / duration, 1);
                
                // Cubic ease-in-out for natural animation
                const eased = progress < 0.5 
                    ? 4 * progress * progress * progress 
                    : 1 - Math.pow(-2 * progress + 2, 3) / 2;
                
                const nextX = eased * totalDeltaX;
                const nextY = eased * totalDeltaY;
                const deltaX = nextX - lastX;
                const deltaY = nextY - lastY;
                lastX = nextX;
                lastY = nextY;
                
                if (Math.abs(deltaX) > 0.01 || Math.abs(deltaY) > 0.01) {
                    target.dispatchEvent(new MouseEvent('mouseover', {
                        clientX: x,
                        clientY: y,
                        bubbles: true
                    }));
                    target.dispatchEvent(new MouseEvent('mousemove', {
                        clientX: x,
                        clientY: y,
                        bubbles: true
                    }));
                    target.dispatchEvent(new WheelEvent('wheel', {
                        deltaX: deltaX,
                        deltaY: deltaY,
                        deltaMode: 0,
                        clientX: x,
                        clientY: y,
                        bubbles: true,
                        cancelable: true
                    }));
                }
                
                if (progress < 1) {
                    requestAnimationFrame(animate);
                } else {
                    // Animation complete, wait for Flutter to update DOM
                    setTimeout(() => resolve(true), 100);
                }
            }
            
            requestAnimationFrame(animate);
        });
    };

    maestro.smoothScrollFlutter = (direction, durationMs = 500) => {
        // Direction-based scrolling - converts direction to deltas and delegates to smoothScrollFlutterByDelta
        const normalizedDirection = (direction || 'UP').toString().toUpperCase();
        const isVertical = normalizedDirection === 'UP' || normalizedDirection === 'DOWN';
        const isHorizontal = normalizedDirection === 'LEFT' || normalizedDirection === 'RIGHT';
        
        if (!isVertical && !isHorizontal) {
            console.error('[Maestro] Unsupported Flutter scroll direction:', direction);
            return Promise.resolve(false);
        }

        const duration = typeof durationMs === 'number' && durationMs > 0 ? durationMs : 500;
        const distance = Math.max(1, Math.round(duration * 2));
        const totalX = normalizedDirection === 'LEFT' ? distance :
            normalizedDirection === 'RIGHT' ? -distance : 0;
        const totalY = normalizedDirection === 'UP' ? distance :
            normalizedDirection === 'DOWN' ? -distance : 0;
        
        return maestro.smoothScrollFlutterByDelta(totalX, totalY, durationMs);
    };
}( window.maestro = window.maestro || {} ));
