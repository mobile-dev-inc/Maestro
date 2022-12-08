import React from 'react';

const CloseIcon = () => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-6 h-6">
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
    </svg>
  )
}

const Banner = ({left, right, onClose}: {
  left: string
  right: string
  onClose: () => void
}) => {
  return (
    <div className="flex justify-between items-center font-bold p-2 pr-5 rounded bg-blue-100 border border-blue-500">
      <div className="flex gap-3 items-center">
        <div className="flex justify-center p-2 rounded items-center hover:bg-blue-900/20 active:bg-blue-900/40" onClick={onClose}>
          <CloseIcon />
        </div>
        <span>{left}</span>
      </div>
      <span>{right}</span>
    </div>
  )
}

export default Banner