import React, { useMemo, useState } from 'react';
import { DeviceScreen, UIElement } from './models';
import { ElementLabel } from './Banner';

const RefreshIcon = (props: React.SVGProps<SVGSVGElement>) => (
  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-6 h-6" {...props}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99" />
  </svg>
)

const compare = (a: string | undefined, b: string | undefined) => {
  if (!a) return b ? 1 : 0
  if (!b) return -1
  return a.localeCompare(b)
}

const SearchIcon = (props: React.SVGProps<SVGSVGElement>) => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
    </svg>
  )
}

const ElementSearch = ({deviceScreen, onElementSelected, hoveredElement, onElementHovered, refresh}: {
  deviceScreen: DeviceScreen
  onElementSelected: (element: UIElement | null) => void
  hoveredElement: UIElement | null
  onElementHovered: (element: UIElement | null) => void,
  refresh: () => void
}) => {
  const [query, setQuery] = useState("");

  const sortedElements = useMemo(() => {
    const filteredElements = deviceScreen.elements.filter(element => {
      if (!element.text && !element.resourceId) return false
      return !query || element.text?.toLowerCase().includes(query.toLowerCase()) || element.resourceId?.toLowerCase().includes(query.toLowerCase())
          || element.hintText?.toLowerCase().includes(query.toLowerCase()) || element.accessibilityText?.toLowerCase().includes(query.toLowerCase())
    })

    return filteredElements.sort((a, b) => {
      const aTextPrefixMatch = query && a.text?.toLowerCase().startsWith(query.toLowerCase())
      const bTextPrefixMatch = query && b.text?.toLowerCase().startsWith(query.toLowerCase())
      if (aTextPrefixMatch && !bTextPrefixMatch) return -1
      if (bTextPrefixMatch && !aTextPrefixMatch) return 1
      return compare(a.text, b.text) || compare(a.resourceId, b.resourceId)
    })
  }, [query, deviceScreen.elements])

  return (
    <div className="flex flex-col h-full gap-3">
      <div className="flex justify-between items-center mb-4">
        <div className="font-bold text-lg">UI Elements</div>
        <button
          className="relative pl-10 cursor-default border border-slate-400 rounded px-4 py-1 hover:bg-slate-100 active:bg-slate-200 dark:hover:bg-slate-850 dark:active:bg-slate-900"
          onClick={refresh}
        >
          Refresh
          <div className="absolute flex pl-3 top-0 left-0 h-full items-center">
            <RefreshIcon className="w-5 top-0"/>
          </div>
        </button>
      </div>
      <div className="flex relative mb-4">
        <div className="flex items-center pl-5 absolute pointer-events-none inset-y-0">
          <SearchIcon className="w-6 text-slate-500 dark:text-slate-300"/>
        </div>
        <input
          type="search"
          placeholder="Search by element text or ID"
          className="rounded w-full p-4 pl-14 bg-slate-100 dark:bg-slate-600 dark:text-white focus:outline-slate-900 dark:focus:outline-slate-100"
          value={query}
          onChange={e => setQuery(e.target.value)}
        />
      </div>
      <div className="flex justify-between">
        <div className="text-slate-400">Text</div>
        <div className="text-slate-400">ID</div>
      </div>
      <div className="flex flex-col overflow-scroll ">
        {sortedElements.map(element => (
          <div
            className="pb-1"
            key={element.id}
            onClick={() => {
              onElementSelected(element)
            }}
            onMouseOver={() => {
              onElementHovered(element)
            }}
            onMouseLeave={() => {
              if (hoveredElement?.id === element.id) {
                onElementHovered(null)
              }
            }}
          >
            <div
              className={`flex gap-3 p-5 items-center rounded border ${hoveredElement === element ? 'bg-slate-100 dark:bg-slate-850' : ''} active:bg-slate-200d dark:active:bg-slate-900 dark:text-white`}
            >
              <ElementLabel text={element.text} cursor="default"/>
              <div className="flex-1"/>
              <ElementLabel text={element.resourceId} cursor="default"/>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

export default ElementSearch
