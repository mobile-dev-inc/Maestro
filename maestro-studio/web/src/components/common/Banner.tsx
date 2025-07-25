const CloseIcon = () => {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.5}
      stroke="currentColor"
      className="w-6 h-6"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M6 18L18 6M6 6l12 12"
      />
    </svg>
  );
};

export const ElementLabel = ({
  text,
  cursor,
}: {
  text: string | undefined;
  cursor?: string | undefined;
}) => {
  return (
    <span
      className={`whitespace-nowrap overflow-hidden text-ellipsis ${
        text ? "" : "text-slate-900/20"
      }`}
      style={{
        cursor: cursor,
      }}
    >
      {text || "—"}
    </span>
  );
};

const Banner = ({
  left,
  right,
  onClose,
}: {
  left: string | undefined;
  right: string | undefined;
  onClose: () => void;
}) => {
  return (
    <div className="flex gap-3 items-center font-bold p-2 pr-5 rounded bg-blue-100 dark:bg-slate-900 dark:text-white dark:border-slate-800 border border-blue-500 overflow-hidden">
      <div
        className="flex justify-center p-2 rounded items-center hover:bg-blue-900/20 active:bg-blue-900/40"
        onClick={onClose}
      >
        <CloseIcon />
      </div>
      <ElementLabel text={left} />
      <div className="flex-1" />
      <ElementLabel text={right} />
    </div>
  );
};

export default Banner;
