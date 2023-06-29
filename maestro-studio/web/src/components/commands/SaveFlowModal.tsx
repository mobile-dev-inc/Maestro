import { FormattedFlow } from "../../helpers/models";
import { useState } from "react";
import CommandInput from "./CommandInput";
import { saveAs } from "file-saver";
import { Modal } from "../common/Modal";
import { Button } from "../design-system/button";

export const SaveFlowModal = ({
  formattedFlow,
  onClose,
}: {
  formattedFlow: FormattedFlow;
  onClose: () => void;
}) => {
  const [config, setConfig] = useState(formattedFlow.config);
  const [commands, setCommands] = useState(formattedFlow.commands);
  const onSave = () => {
    const content = `${config}\n---\n${commands}`;
    saveAs(
      new Blob([content], { type: "text/yaml;charset=utf-8" }),
      "Flow.yaml"
    );
    onClose();
  };

  return (
    <Modal onClose={onClose}>
      <div className="flex flex-col gap-3 p-8 h-full bg-white dark:bg-slate-900 dark:text-white rounded-lg">
        <span className="text-lg font-bold">Save Flow to File</span>
        <div className="flex flex-col h-full rounded gap-2">
          <CommandInput value={config} setValue={setConfig} />
          <div className="flex-grow">
            <CommandInput
              value={commands}
              resize="vertical"
              className="h-full"
              setValue={setCommands}
            />
          </div>
        </div>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose} type="button" size="md" variant="tertiary">
            Cancel
          </Button>
          <Button onClick={onSave} type="button" size="md" variant="primary">
            Save
          </Button>
        </div>
      </div>
    </Modal>
  );
};
