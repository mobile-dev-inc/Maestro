import React, {useLayoutEffect} from "react";
import {twMerge} from "tailwind-merge";
import {Input} from "../design-system/input";

const GlobeIcon = ({ className }: { className?: string }) => (
  <svg
    className={className}
    width="24"
    height="24"
    viewBox="0 0 256 256"
  >
    <path
      fill="currentColor"
      d="M128,24h0A104,104,0,1,0,232,128,104.12,104.12,0,0,0,128,24Zm88,104a87.61,87.61,0,0,1-3.33,24H174.16a157.44,157.44,0,0,0,0-48h38.51A87.61,87.61,0,0,1,216,128ZM102,168H154a115.11,115.11,0,0,1-26,45A115.27,115.27,0,0,1,102,168Zm-3.9-16a140.84,140.84,0,0,1,0-48h59.88a140.84,140.84,0,0,1,0,48ZM40,128a87.61,87.61,0,0,1,3.33-24H81.84a157.44,157.44,0,0,0,0,48H43.33A87.61,87.61,0,0,1,40,128ZM154,88H102a115.11,115.11,0,0,1,26-45A115.27,115.27,0,0,1,154,88Zm52.33,0H170.71a135.28,135.28,0,0,0-22.3-45.6A88.29,88.29,0,0,1,206.37,88ZM107.59,42.4A135.28,135.28,0,0,0,85.29,88H49.63A88.29,88.29,0,0,1,107.59,42.4ZM49.63,168H85.29a135.28,135.28,0,0,0,22.3,45.6A88.29,88.29,0,0,1,49.63,168Zm98.78,45.6a135.28,135.28,0,0,0,22.3-45.6h35.66A88.29,88.29,0,0,1,148.41,213.6Z"></path>
  </svg>
);

const BrowserActionBar = ({currentUrl, onUrlUpdated, isLoading}: {
  currentUrl?: string,
  onUrlUpdated: (url: string) => void,
  isLoading: boolean
}) => {
  const [isEditing, setIsEditing] = React.useState(false)
  const [editedUrl, setEditedUrl] = React.useState(currentUrl)
  useLayoutEffect(() => {
    if (!isEditing && !isLoading) {
      setEditedUrl(currentUrl)
    }
  }, [isLoading, isEditing, currentUrl]);
  return (
    <div className="w-full relative">
      <div className="inset-y-0 absolute flex items-center px-1.5">
        <GlobeIcon className="text-gray-300" />
      </div>
      <Input
        className={twMerge(
          "w-full pl-8 pr-1 py-0.5 rounded-full border-2 bg-slate-50 dark:bg-gray-800",
          isLoading && "bg-gray-100",
        )}
        size="sm"
        disabled={isLoading}
        value={(isEditing || isLoading) ? editedUrl : currentUrl}
        onChange={(e) => setEditedUrl(e.target.value)}
        onFocus={() => setIsEditing(true)}
        onBlur={() => setIsEditing(false)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && isEditing && editedUrl) {
            onUrlUpdated(editedUrl);
            e.currentTarget.blur();
            setIsEditing(false);
          }
        }}
      />
    </div>
  )
}

export default BrowserActionBar