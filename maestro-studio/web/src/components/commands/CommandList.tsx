import { ReplCommand } from "../../helpers/models";
import CommandRow from "./CommandRow";
import { DragDropContext, Droppable, Draggable } from "react-beautiful-dnd";
import { Button } from "../design-system/button";
import clsx from "clsx";
import { twMerge } from "tailwind-merge";

interface CommandListProps {
  commands: ReplCommand[];
  selectedIds: string[];
  onReorder: (newOrder: ReplCommand[]) => void;
  updateSelected: (id: any) => void;
}

export default function CommandList({
  commands,
  selectedIds,
  updateSelected,
  onReorder,
}: CommandListProps) {
  const handleDragEnd = (result: any) => {
    if (!result.destination) return;
    let newCommandOrder = commands;
    const [reorderedItem] = newCommandOrder.splice(result.source.index, 1);
    newCommandOrder.splice(result.destination.index, 0, reorderedItem);
    onReorder([...newCommandOrder]);
  };

  return (
    <DragDropContext onDragEnd={handleDragEnd}>
      <Droppable droppableId="commands">
        {(provided) => (
          <div {...provided.droppableProps} ref={provided.innerRef}>
            {commands.map((command: ReplCommand, index) => (
              <Draggable
                key={command.id}
                draggableId={command.id}
                index={index}
              >
                {(provided, snapshot) => (
                  <div
                    ref={provided.innerRef}
                    {...provided.draggableProps}
                    className={twMerge(
                      clsx(
                        "group relative flex flex-row bg-white rounded-lg border border-transparent dark:bg-slate-900 dark:active:bg-slate-900 overflow-hidden",
                        snapshot.isDragging &&
                          "shadow-md border-slate-100 dark:border-slate-700"
                      )
                    )}
                  >
                    <div
                      {...provided.dragHandleProps}
                      className="opacity-0 group-hover:opacity-100 group-active:opacity-100 transition-opacity"
                    >
                      <Button
                        variant="quaternary"
                        className="text-gray-900/40 dark:text-white/40 py-4 my-0.5 pointer-events-none"
                        tabIndex={-1}
                        size="xs"
                        iconElement={
                          <svg
                            className="w-5 h-5 min-w-[20px]"
                            xmlns="http://www.w3.org/2000/svg"
                            viewBox="0 0 24 24"
                          >
                            <path
                              fill="currentColor"
                              d="M8.5 7C9.32843 7 10 6.32843 10 5.5C10 4.67157 9.32843 4 8.5 4C7.67157 4 7 4.67157 7 5.5C7 6.32843 7.67157 7 8.5 7ZM8.5 13.5C9.32843 13.5 10 12.8284 10 12C10 11.1716 9.32843 10.5 8.5 10.5C7.67157 10.5 7 11.1716 7 12C7 12.8284 7.67157 13.5 8.5 13.5ZM10 18.5C10 19.3284 9.32843 20 8.5 20C7.67157 20 7 19.3284 7 18.5C7 17.6716 7.67157 17 8.5 17C9.32843 17 10 17.6716 10 18.5ZM15.5 7C16.3284 7 17 6.32843 17 5.5C17 4.67157 16.3284 4 15.5 4C14.6716 4 14 4.67157 14 5.5C14 6.32843 14.6716 7 15.5 7ZM17 12C17 12.8284 16.3284 13.5 15.5 13.5C14.6716 13.5 14 12.8284 14 12C14 11.1716 14.6716 10.5 15.5 10.5C16.3284 10.5 17 11.1716 17 12ZM15.5 20C16.3284 20 17 19.3284 17 18.5C17 17.6716 16.3284 17 15.5 17C14.6716 17 14 17.6716 14 18.5C14 19.3284 14.6716 20 15.5 20Z"
                            ></path>
                          </svg>
                        }
                      />
                    </div>
                    <CommandRow
                      isDragging={snapshot.isDragging}
                      command={command}
                      selected={selectedIds.includes(command.id)}
                      onClick={() => updateSelected(command.id)}
                    />
                  </div>
                )}
              </Draggable>
            ))}
            {provided.placeholder}
          </div>
        )}
      </Droppable>
    </DragDropContext>
  );
}
