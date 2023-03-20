import React, { useState } from 'react';
import { DeviceScreen, UIElement } from './models';
import { CopyToClipboard } from 'react-copy-to-clipboard';

const isBoundsEmpty = (element: UIElement): boolean => {
  return !element.bounds?.width || !element.bounds?.height
}

export const CodeSnippet = ({children}: {
  children: string
}) => {
  const [hovered, setHovered] = useState(false)
  const [copied, setCopied] = useState(false)
  return (
    <CopyToClipboard text={children} onCopy={() => setCopied(true)}>
      <div
        className="relative bg-slate-100 dark:bg-slate-600 dark:hover:bg-slate-700 dark:active:bg-slate-800 rounded border border-transparent hover:border-slate-500 active:bg-slate-200 cursor-context-menu"
        onMouseOver={() => setHovered(true)}
        onMouseLeave={() => {
          setHovered(false)
          setCopied(false)
        }}
      >
        <pre className="overflow-x-scroll p-5">
          {children}
        </pre>
        {hovered ? (
          <div className="absolute top-0 right-0 flex">
            <span className="px-2 py-1 font-sans text-slate-400 dark:text-white">{copied ? 'copied' : 'click to copy'}</span>
          </div>
        ) : null}
      </div>
    </CopyToClipboard>
  );
}

const Section = ({ deviceScreen, element, title, documentationUrl, codeSnippets }: {
  deviceScreen: DeviceScreen
  element: UIElement
  title: string
  documentationUrl: string
  codeSnippets: string[]
}) => {
  const toPercent = (n: number, total: number) => `${Math.round((100 * n / total))}%`
  const elementHasResourceIdIndex = typeof element.resourceIdIndex === 'number'
  const elementHasTextIndex = typeof element.textIndex === 'number'
  const codeSnippetComponents = codeSnippets.map(codeSnippet => {
    // If the snippet references a resource id but the element doesn't have one, skip it
    if (codeSnippet.includes('[id]') && !element.resourceId) return null
    // If the snippet references a resource id index but the element doesn't have one, skip it
    if (codeSnippet.includes('[resource-id-index]') && !elementHasResourceIdIndex) return null
    // If the snippet references a hintText but the element doesn't have one, skip it
    if (codeSnippet.includes('[hintText]') && !element.hintText) return null
    // If the snippet references a accessibilityText but the element doesn't have one, skip it
    if (codeSnippet.includes('[accessibilityText]') && !element.accessibilityText) return null
    // If the snippet references text index but the element doesn't have any, skip it
    if (codeSnippet.includes('[text]') && !element.text) return null
    // If the snippet references a text id index but the element doesn't have one, skip it
    if (codeSnippet.includes('[text-index]') && !elementHasTextIndex) return null
    // If the snippet references bounds but the element doesn't have any, skip it
    if (codeSnippet.includes('[bounds]') && !element.bounds) return null
    // If the snippet references tapOn but the element doesn't have bounds, skip it
    if (codeSnippet.includes('tapOn') && isBoundsEmpty(element)) return null
    // If the element has a resource id index, and the snippet doesn't specify one, skip it
    if (elementHasResourceIdIndex && codeSnippet.includes('[id]') && !codeSnippet.includes('[resource-id-index]')) return null
    // If the element has a text id index, and the snippet doesn't specify one, skip it
    if (elementHasTextIndex && codeSnippet.includes('[text]') && !codeSnippet.includes('[text-index]')) return null

    const id = element.resourceId || ''
    const text = element.text || ''
    const hintText = element.hintText || ''
    const accessibilityText = element.accessibilityText || ''
    const resourceIdIndex = `${element.resourceIdIndex}`
    const textIndex = `${element.textIndex}`
    const bounds = element.bounds || { x: 0, y: 0, width: 0, height: 0 }
    const cx = toPercent(bounds.x + bounds.width / 2, deviceScreen.width)
    const cy = toPercent(bounds.y + bounds.height / 2, deviceScreen.height)
    const point = `${cx},${cy}`
    return (
      <CodeSnippet>
        {
          codeSnippet
            .replace('[id]', id)
            .replace('[text]', text.replace("\n", " "))
            .replace('[point]', point)
            .replace('[resource-id-index]', resourceIdIndex)
            .replace('[text-index]', textIndex)
            .replace('[hintText]', hintText.replace("\n", " "))
            .replace('[accessibilityText]', accessibilityText.replace("\n", " "))
        }
      </CodeSnippet>
    )
  }).filter(c => c)
  if (codeSnippetComponents.length === 0) return null
  return (
    <div className="flex flex-col gap-3">
      <div className="flex gap-2 justify-between">
        <span className="text-slate-500 whitespace-nowrap">{title}</span>
        <a
          className="text-blue-400 underline underline-offset-2 whitespace-nowrap"
          href={documentationUrl}
          target="_blank"
          rel="noopener noreferrer"
        >View Documentation</a>
      </div>
      {codeSnippetComponents}
    </div>
  )
}

const Error = ({ element }: {
  element: UIElement
}) => {
  const content = (() => {
    if (isBoundsEmpty(element)) {
      const openAGitHubIssueLink = (
        <a
          className="underline"
          href="https://github.com/mobile-dev-inc/maestro/issues/new"
          target="_blank"
          rel="noopener noreferrer"
        >
          open a github issue
        </a>
      )
      return (
        <span>This element's <b>width or height is 0</b> so you won't be able to tap on it. If you believe this is a problem with Maestro, please {openAGitHubIssueLink} and include a screenshot of this page.</span>
      );
    }
    if (!element.text && !element.resourceId) {
      return (
        <span>This element does not have any <b>text</b> or <b>resource id</b> associated with it. You can still tap on this element using screen percentages, but this won't be as resilient to changes to your UI.</span>
      )
    }
  })()
  if (!content) return null
  return (
    <div className="bg-red-100 rounded-md p-5">
      {content}
    </div>
  )
}

const Examples = ({ deviceScreen, element }: {
  deviceScreen: DeviceScreen
  element: UIElement
}) => {
  const sections = template.split('===\n').map(section => {
    const paragraphs = section.split('---\n');
    const header = paragraphs.shift()!.split(',')
    const title = header[0]
    const documentationUrl = header[1]
    return (
      <Section
        deviceScreen={deviceScreen}
        element={element}
        title={title}
        documentationUrl={documentationUrl}
        codeSnippets={paragraphs}
      />
    )
  })

  return (
    <div
      className="flex flex-col gap-5 h-full overflow-y-scroll"
    >
      <Error element={element} />
      <div className="font-bold">
        Here are some examples of how you can interact with this element:
      </div>
      {sections}
    </div>
  )
}

const template = `
Tap,https://maestro.mobile.dev/reference/tap-on-view
---
- tapOn: "[text]"
---
- tapOn: "[hintText]"
---
- tapOn: "[accessibilityText]"
---
- tapOn:
    text: "[text]"
    index: [text-index]
---
- tapOn:
    id: "[id]"
---
- tapOn:
    id: "[id]"
    index: [resource-id-index]
---
- tapOn:
    point: "[point]"
===
Assertion,https://maestro.mobile.dev/reference/assertions
---
- assertVisible: "[text]"
---
- assertVisible:
    text: "[text]"
    index: [text-index]
---
- assertVisible:
    id: "[id]"
---
- assertVisible:
    id: "[id]"
    index: [resource-id-index]
---
- assertVisible:
    text: "[hintText]"
---
- assertVisible:
    text: "[accessibilityText]"
===
Conditional,https://maestro.mobile.dev/advanced/conditions
---
- runFlow:
    when:
      visible: "[text]"
    file: Subflow.yaml
---
- runFlow:
    when:
      visible:
        text: "[text]"
        index: [text-index]
    file: Subflow.yaml
---
- runFlow:
    when:
      visible:
        id: "[id]"
    file: Subflow.yaml
---
- runFlow:
    when:
      visible:
        id: "[id]"
        index: [text-index]
    file: Subflow.yaml
`

export default Examples
