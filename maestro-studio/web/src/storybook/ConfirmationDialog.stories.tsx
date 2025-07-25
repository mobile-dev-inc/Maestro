import { ConfirmationDialog } from "../components/common/ConfirmationDialog";

export default {
  title: "ConfirmationDialog",
};

export const Main = () => {
  return (
    <ConfirmationDialog title="This is the title" content="This is the content">
      <button className="px-4 py-1 border bg-blue-700 text-white rounded cursor-default hover:bg-blue-800 active:bg-blue-900">
        Show modal
      </button>
    </ConfirmationDialog>
  );
};
