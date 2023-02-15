import { API } from './api';
import React, { memo, ReactElement, useEffect, useRef, useState } from 'react';
import AutosizingTextArea from './AutosizingTextArea';
import { FormattedFlow, ReplCommand, ReplCommandStatus } from './models';
import { Reorder } from 'framer-motion';
import { CopyToClipboard } from 'react-copy-to-clipboard';
import { SaveFlowModal } from './SaveFlowModal';
import { useConfirmationDialog } from './ConfirmationDialog';

const PlayIcon = () => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="w-6 h-6">
      <path fillRule="evenodd" d="M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z" clipRule="evenodd" />
    </svg>
  )
}

const CheckIcon = () => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="w-5 h-5 text-green-600">
      <path fillRule="evenodd" d="M19.916 4.626a.75.75 0 01.208 1.04l-9 13.5a.75.75 0 01-1.154.114l-6-6a.75.75 0 011.06-1.06l5.353 5.353 8.493-12.739a.75.75 0 011.04-.208z" clipRule="evenodd" />
    </svg>
  )
}

const ErrorIcon = () => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="w-5 h-5 text-red-600">
      <path fillRule="evenodd" d="M5.47 5.47a.75.75 0 011.06 0L12 10.94l5.47-5.47a.75.75 0 111.06 1.06L13.06 12l5.47 5.47a.75.75 0 11-1.06 1.06L12 13.06l-5.47 5.47a.75.75 0 01-1.06-1.06L10.94 12 5.47 6.53a.75.75 0 010-1.06z" clipRule="evenodd" />
    </svg>
  )
}

const LoadingIcon = () => {
  return (
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-5 h-5 animate-spin">
      <path d="M23.9997 11.9187C24.0446 18.546 18.7085 23.9548 12.0812 23.9997C5.45398 24.0446 0.0451292 18.7085 0.000234429 12.0813C-0.0446603 5.454 5.29141 0.0451486 11.9187 0.000253852C18.5459 -0.0446409 23.9548 5.29142 23.9997 11.9187ZM3.20567 12.0596C3.23857 16.9165 7.20258 20.8272 12.0595 20.7943C16.9165 20.7614 20.8272 16.7974 20.7942 11.9404C20.7613 7.08345 16.7973 3.17279 11.9404 3.20569C7.08343 3.23859 3.17277 7.2026 3.20567 12.0596Z" fill="#2563EB" fillOpacity="0.4"/>
      <path d="M23.9997 11.9187C24.0168 14.4512 23.2323 16.9242 21.7584 18.9837C20.2846 21.0432 18.1969 22.5836 15.7942 23.3843C13.3916 24.1851 10.7972 24.2052 8.38251 23.4417C5.96776 22.6783 3.85647 21.1704 2.35085 19.134L4.92838 17.2283C6.0318 18.7208 7.57912 19.8258 9.34882 20.3854C11.1185 20.9449 13.0199 20.9301 14.7807 20.3433C16.5415 19.7564 18.0715 18.6275 19.1517 17.1182C20.2319 15.6088 20.8068 13.7964 20.7942 11.9404L23.9997 11.9187Z" fill="#2563EB"/>
    </svg>
  )
}

const CheckBox = ({type, checked, onChange}: {
  type: 'circle' | 'square'
  checked: boolean
  onChange?: (checked: boolean) => void
}) => {
  const onClick = () => {
    onChange && onChange(!checked)
  }
  if (type === 'circle') {
    if (checked) {
      return (
        <svg
          viewBox="0 0 20 20"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-5 h-5"
          onClick={onClick}
        >
          <rect width="20" height="20" rx="10" fill="#2563EB"/>
          <path d="M7 10.25L9.6 12.85L13.5 7" stroke="white" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      )
    } else {
      return (
        <svg
          viewBox="0 0 20 20"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-5 h-5"
          onClick={onClick}
        >
          <rect x="0.5" y="0.5" width="19" height="19" rx="9.5" stroke="#CBD5E1"/>
        </svg>
      );
    }
  } else {
    if (checked) {
      return (
        <svg
          viewBox="0 0 20 21"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-5 h-5 text-slate-900 hover:text-slate-600 active:text-slate-400"
          onClick={onClick}
        >
          <rect width="20" height="20" transform="translate(0 0.5)" fill="currentColor"/>
          <path d="M7 10.75L9.6 13.35L13.5 7.5" stroke="white" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      )
    } else {
      return (
        <svg
          viewBox="0 0 20 21"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-5 h-5 text-transparent hover:text-slate-300 active:text-slate-400"
          onClick={onClick}
        >
          <rect x="0.5" y="1" width="19" height="19" stroke="#CBD5E1" fill="currentColor"/>
        </svg>
      )
    }
  }
}

const StatusIcon = ({status}: {
  status: ReplCommandStatus
}): ReactElement | null => {
  switch (status) {
    case 'success': return <CheckIcon />
    case 'canceled': return null
    case 'error': return <ErrorIcon />
    case 'pending': return null
    case 'running': return <LoadingIcon />
  }
}

const CommandRow = ({command, selected, onClick}: {
  command: ReplCommand
  selected: boolean
  onClick: () => void
}) => {
  return (
    <div
      key={command.id}
      className="relative flex flex-row border-b hover:bg-slate-50 active:bg-slate-100 data-[running]:bg-blue-50"
      onClick={onClick}
      data-running={command.status === 'running' ? true : undefined}
    >
      <div
        className="flex flex-col px-2 pt-4 border-r"
      >
        <CheckBox type="circle" checked={selected} />
      </div>
      <pre className="p-4 pr-14 font-mono cursor-default flex-1 overflow-x-scroll">{command.yaml}</pre>
      <div className="absolute p-4 right-0">
        <StatusIcon status={command.status} />
      </div>
    </div>
  )
}

const PlayIconSmall = () => {
  return (
    <svg viewBox="0 0 12 12" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-3.5 h-3.5">
      <path d="M1.07422 1.69354C1.07422 1.11266 1.69649 0.744861 2.20544 1.02444L10.0364 5.33217C10.1561 5.39807 10.2559 5.49489 10.3255 5.61252C10.395 5.73015 10.4316 5.86428 10.4316 6.00092C10.4316 6.13756 10.395 6.27169 10.3255 6.38932C10.2559 6.50696 10.1561 6.60377 10.0364 6.66968L2.20544 10.9767C2.08923 11.0406 1.95839 11.0731 1.8258 11.0711C1.69321 11.069 1.56344 11.0325 1.44928 10.965C1.33511 10.8975 1.24049 10.8015 1.17472 10.6864C1.10895 10.5712 1.07432 10.4409 1.07422 10.3083V1.69354V1.69354Z" stroke="#0F172A" strokeWidth="1.20135" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  )
}

const ExportIconSmall = () => {
  return (
    <svg viewBox="0 0 11 14" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-[1.15rem] h-[1.15rem]">
      <path d="M3.18652 3.76739L5.50079 1.45312L7.81505 3.76739" stroke="black" strokeWidth="0.881624" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M5.50098 7.62449V1.45312" stroke="black" strokeWidth="0.881624" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M8.14576 5.86121H9.4682C9.58511 5.86121 9.69723 5.90765 9.7799 5.99032C9.86257 6.07299 9.90901 6.18511 9.90901 6.30202V12.0326C9.90901 12.1495 9.86257 12.2616 9.7799 12.3443C9.69723 12.4269 9.58511 12.4734 9.4682 12.4734H1.53359C1.41667 12.4734 1.30455 12.4269 1.22188 12.3443C1.13922 12.2616 1.09277 12.1495 1.09277 12.0326V6.30202C1.09277 6.18511 1.13922 6.07299 1.22188 5.99032C1.30455 5.90765 1.41667 5.86121 1.53359 5.86121H2.85602" stroke="black" strokeWidth="0.881624" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  )
}

const CopyIconSmall = () => {
  return (
    <svg viewBox="0 0 11 14" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-4 h-4">
      <g clipPath="url(#clip0_3088_2750)">
        <path d="M7.54914 2.20715H9.84975C9.97178 2.20715 10.0888 2.25563 10.1751 2.34192C10.2614 2.42821 10.3099 2.54524 10.3099 2.66728V12.3299C10.3099 12.4519 10.2614 12.5689 10.1751 12.6552C10.0888 12.7415 9.97178 12.79 9.84975 12.79H1.56754C1.44551 12.79 1.32848 12.7415 1.24219 12.6552C1.1559 12.5689 1.10742 12.4519 1.10742 12.3299V2.66728C1.10742 2.54524 1.1559 2.42821 1.24219 2.34192C1.32848 2.25563 1.44551 2.20715 1.56754 2.20715H3.86816" stroke="black" strokeWidth="0.920245" strokeLinecap="round" strokeLinejoin="round"/>
        <path d="M3.40723 4.04748V3.58736C3.40723 2.9772 3.64961 2.39203 4.08106 1.96058C4.51251 1.52913 5.09768 1.28674 5.70784 1.28674C6.318 1.28674 6.90317 1.52913 7.33462 1.96058C7.76607 2.39203 8.00845 2.9772 8.00845 3.58736V4.04748H3.40723Z" stroke="black" strokeWidth="0.920245" strokeLinecap="round" strokeLinejoin="round"/>
      </g>
      <defs>
        <clipPath id="clip0_3088_2750">
          <rect width="10.2761" height="12.5" fill="white" transform="translate(0.570312 0.75)"/>
        </clipPath>
      </defs>
    </svg>
  )
}

const DeleteIconSmall = () => {
  return (
    <svg viewBox="0 0 11 12" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-3.5 h-3.5">
      <path d="M6.94335 4.33589L6.7514 9.32861M4.09528 9.32861L3.90333 4.33589M9.43305 2.55516C9.62277 2.584 9.81139 2.61451 10 2.64724M9.43305 2.55571L8.84058 10.2567C8.8164 10.5703 8.67475 10.8631 8.44395 11.0768C8.21315 11.2904 7.91022 11.409 7.59573 11.4089H3.25095C2.93646 11.409 2.63353 11.2904 2.40273 11.0768C2.17193 10.8631 2.03028 10.5703 2.0061 10.2567L1.41363 2.55516M9.43305 2.55516C8.7928 2.45836 8.14924 2.3849 7.50364 2.33492M0.84668 2.64669C1.03529 2.61396 1.22391 2.58345 1.41363 2.55516M1.41363 2.55516C2.05388 2.45836 2.69744 2.3849 3.34304 2.33492M7.50364 2.33492V1.82677C7.50364 1.17217 6.99882 0.626303 6.34422 0.605777C5.73046 0.58616 5.11622 0.58616 4.50246 0.605777C3.84786 0.626303 3.34304 1.17273 3.34304 1.82677V2.33492M7.50364 2.33492C6.11884 2.2279 4.72784 2.2279 3.34304 2.33492" stroke="black" strokeWidth="0.83212" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  )
}

const HeaderButton = ({text, icon, onClick}: {
  text: string
  icon: ReactElement
  onClick: () => void
}) => {
  return (
    <div
      className="flex items-center text-sm px-2.5 gap-1.5 border-l border-slate-300 select-none hover:bg-slate-200 active:bg-slate-300"
      onClick={onClick}
    >
      {icon}
      {text}
    </div>
  )
}

const ReplHeader = ({onSelectAll, onDeselectAll, selected, copyText, onPlay, onExport, onCopy, onDelete}: {
  onSelectAll: () => void
  onDeselectAll: () => void
  selected: number
  copyText: string
  onPlay: () => void
  onExport: () => void
  onCopy: () => void
  onDelete: () => void
}) => {
  return (
    <div
      className="flex border-b bg-slate-100"
    >
      <div
        className="flex flex-col p-2 border-r border-slate-300"
      >
        <CheckBox
          type="square"
          checked={selected > 0}
          onChange={checked => {
            if (checked) {
              onSelectAll()
            } else {
              onDeselectAll()
            }
          }}
        />
      </div>
      <div className="flex flex-1 gap-2 justify-between">
        <span className="px-4 whitespace-nowrap data-[selectall=true]:text-slate-400 select-none self-center" data-selectall={selected === 0}>
          {selected > 0 ? `${selected} Selected` : 'Select All'}
        </span>
        {selected > 0 && (
          <div className="flex">
            <HeaderButton text="Play" icon={<PlayIconSmall />} onClick={onPlay}/>
            <HeaderButton text="Export" icon={<ExportIconSmall />} onClick={onExport}/>
            <CopyToClipboard text={copyText}>
              <HeaderButton text="Copy" icon={<CopyIconSmall />} onClick={onCopy}/>
            </CopyToClipboard>
            <HeaderButton text="Delete" icon={<DeleteIconSmall />} onClick={onDelete}/>
          </div>
        )}
      </div>
    </div>
  );
}

const getFlowText = (selected: ReplCommand[]): string => {
  return selected.map(c => (
    c.yaml.endsWith('\n') ? c.yaml : `${c.yaml}\n`
  )).join('')
}

const Instructions = () => {
  return (
    <div className="flex-1 flex flex-col p-16 items-center">
      <div className="flex flex-col gap-4 font-mono p-12 bg-blue-50 rounded-md border border-blue-400 text-blue-900">
        <p>• Type a command above, then hit ENTER to run</p>
        <p>• Tap on the device screen to explore available commands</p>
        <p>• Hold CMD (⌘) down to freely tap and swipe on the device screen</p>
      </div>
    </div>
  )
}

const ReplView = ({input, onInput, onError}: {
  input: string
  onInput: (input: string) => void
  onError: (error: string | null) => void
}) => {
  const listRef = useRef<HTMLElement>()
  const [_selected, setSelected] = useState<string[]>([])
  const [dragging, setDragging] = useState(false)
  const [formattedFlow, setFormattedFlow] = useState<FormattedFlow | null>(null)
  const {error, repl} = API.repl.useRepl()
  const listSize = repl?.commands.length || 0
  const previousListSize = useRef(0);

  const [showConfirmationDialog, Dialog] = useConfirmationDialog(() => API.repl.deleteCommands(selectedIds));

  // Scroll to bottom when new commands are added
  useEffect(() => {
    const listSizeChange = listSize - previousListSize.current
    if (listSizeChange > 0 && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight
    }
    previousListSize.current = listSize
  }, [listSize])

  if (error) {
    return (
      <div>Error fetching repl</div>
    )
  }

  if (!repl) {
    return null
  }

  const selectedCommands = _selected.map(id => repl.commands.find(c => c.id === id)).filter((c): c is ReplCommand => !!c)
  const selectedIds = selectedCommands.map(c => c.id)

  const runCommand = async () => {
    if (!input) return
    onError(null)
    try {
      await API.repl.runCommand(input)
      onInput("")
    } catch (e: any) {
      onError(e.message || 'Failed to run command')
    }
  }

  const onReorder = (newOrder: ReplCommand[]) => {
    API.repl.reorderCommands(newOrder.map(c => c.id))
  }

  const onPlay = () => {
    API.repl.runCommandsById(selectedIds)
  }

  const onExport = () => {
    if (selectedIds.length === 0) return
    API.repl.formatFlow(selectedIds).then(setFormattedFlow)
  }
  const onCopy = () => {}
  const onDelete = () => {
    showConfirmationDialog()
  }

  const flowText = getFlowText(selectedCommands);

  return (
    <>
      <div className="flex flex-col border rounded overflow-hidden h-full">
        <ReplHeader
          onSelectAll={() => setSelected(repl.commands.map(c => c.id))}
          onDeselectAll={() => setSelected([])}
          selected={selectedIds.length}
          copyText={flowText}
          onPlay={onPlay}
          onExport={onExport}
          onCopy={onCopy}
          onDelete={onDelete}
        />
        <Reorder.Group
          ref={listRef}
          className="overflow-y-scroll overflow-hidden"
          onReorder={onReorder}
          values={repl.commands}
        >
          {repl.commands.map(command => (
            <Reorder.Item
              value={command}
              key={command.id}
              transition={{duration: .1}}
              dragTransition={{bounceStiffness: 2000, bounceDamping: 100}}
              onDragStart={() => { setDragging(true) }}
              onDragEnd={() => { setTimeout(() => setDragging(false)) }}
            >
              <CommandRow
                command={command}
                selected={selectedIds.includes(command.id)}
                onClick={() => {
                  if (dragging) return
                  if (selectedIds.includes(command.id)) {
                    setSelected(prevState => prevState.filter(id => id !== command.id))
                  } else {
                    setSelected(prevState => [...prevState, command.id])
                  }
                }}
              />
            </Reorder.Item>
          ))}
        </Reorder.Group>
        <div
          className="relative flex flex-col"
          onKeyDown={e => {
            if (e.code === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              runCommand()
            }
          }}
        >
          <AutosizingTextArea
            className="resize-none p-4 pr-16 overflow-y-scroll overflow-hidden font-mono cursor-text outline-none border border-transparent border-b-slate-200 focus:border focus:border-slate-400"
            setValue={value => onInput(value)}
            value={input}
            placeholder="Enter a command, then press ENTER to run"
          />
          <button
            className="absolute flex items-center right-1 top-1 rounded bottom-1 px-4 disabled:text-slate-400 enabled:text-blue-600 enabled:hover:bg-slate-200 enabled:active:bg-slate-300 cursor-default"
            disabled={!input}
            onClick={() => runCommand()}
          >
            <PlayIcon />
          </button>
        </div>
        {repl.commands.length === 0 && (
          <Instructions />
        )}
        {formattedFlow && (
          <SaveFlowModal formattedFlow={formattedFlow} onClose={()=>{ setFormattedFlow(null) }} />
        )}
      </div>

      <Dialog 
        title={`Delete (${selectedIds.length}) command${selectedIds.length === 1 ? '' : 's'}?`} 
        content={`Click confirm to delete the selected command${selectedIds.length === 1 ? '' : 's'}.`} 
      />
    </>
  )
}

export default memo(ReplView)
