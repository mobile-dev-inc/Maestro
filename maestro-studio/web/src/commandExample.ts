import { DeviceScreen, UIElement } from './models';
import YAML from 'yaml';

export type CommandExample = {
  status: 'available' | 'unavailable'
  title: string
  content: string
  documentation: string
}

type Selector = {
  title: string
  status: 'available'
  definition: any
  documentation?: string
} | {
  title: string
  status: 'unavailable'
  message: string
  documentation?: string
}

const toPercent = (n: number, total: number) => `${Math.round((100 * n / total))}%`

const getCoordinatesSelector = (deviceWidth: number, deviceHeight: number, uiElement: UIElement): Selector => {
  const bounds = uiElement.bounds || { x: 0, y: 0, width: 0, height: 0 }
  const cx = toPercent(bounds.x + bounds.width / 2, deviceWidth)
  const cy = toPercent(bounds.y + bounds.height / 2, deviceHeight)
  return {
    status: 'available',
    title: 'Coordinates',
    definition: { point: `${cx},${cy}` }
  }
}

const getSelectors = (uiElement: UIElement, deviceScreen: DeviceScreen): Selector[] => {
  const selectors: Selector[] = []
  if (uiElement.resourceId) {
    if (typeof uiElement.resourceIdIndex === 'number') {
      selectors.push({
        title: 'Resource Id',
        status: 'available',
        definition: {
          id: uiElement.resourceId,
          index: uiElement.resourceIdIndex,
        }
      })
    } else {
      selectors.push({
        title: 'Resource Id',
        status: 'available',
        definition: {
          id: uiElement.resourceId,
        }
      })
    }
  } else {
    const elementsWithSameBounds = deviceScreen.elements.filter((element) => {
      if (element.resourceId === null || element.resourceId === undefined)
        return false;
      return (
        element.bounds?.width === uiElement.bounds?.width &&
        element.bounds?.height === uiElement.bounds?.height
      );
    });
    if (elementsWithSameBounds.length > 0) {
      elementsWithSameBounds.forEach((element) => {
        selectors.push({
          title: "Resource Id",
          status: "available",
          definition: {
            id: element.resourceId,
          },
        });
      });
    } else {
      selectors.push({
        title: "Resource Id",
        status: "unavailable",
        message:
          "This element has no resource id associated with it. Type ‘\u2318 D’ to view platform-specific documentation on how to assign resource ids to ui elements.",
        documentation:
          "https://maestro.mobile.dev/platform-support/supported-platforms",
      });
    }
  }
  if (uiElement.text) {
    if (typeof uiElement.textIndex === 'number') {
      selectors.push({
        title: 'Text',
        status: 'available',
        definition: {
          text: uiElement.text,
          index: uiElement.textIndex,
        }
      })
    } else {
      selectors.push({
        title: 'Text',
        status: 'available',
        definition: uiElement.text
      })
    }
  }
  if (uiElement.hintText) {
    selectors.push({
      title: 'Hint Text',
      status: 'available',
      definition: uiElement.hintText
    })
  }
  if (uiElement.accessibilityText) {
    selectors.push({
      title: 'Accessibility Text',
      status: 'available',
      definition: uiElement.accessibilityText
    })
  }
  return selectors
}

const toTapExample = (selector: Selector): CommandExample => {
  return {
    status: selector.status,
    title: `Tap > ${selector.title}`,
    content: selector.status === 'available' ? YAML.stringify([{ tapOn: selector.definition }]) : selector.message,
    documentation: selector.documentation || 'https://maestro.mobile.dev/reference/tap-on-view',
  }
}

const toAssertExample = (selector: Selector): CommandExample => {
  return {
    status: selector.status,
    title: `Assert > ${selector.title}`,
    content: selector.status === 'available' ? YAML.stringify([{ assertVisible: selector.definition }]) : selector.message,
    documentation: selector.documentation || 'https://maestro.mobile.dev/reference/assertions',
  }
}

const toConditionalExample = (selector: Selector): CommandExample => {
  return {
    status: selector.status,
    title: `Conditional > ${selector.title}`,
    content: selector.status === 'available' ? YAML.stringify([{ runFlow: { when: { visible: selector.definition }, file: 'Subflow.yaml' } }]) : selector.message,
    documentation: selector.documentation || 'https://maestro.mobile.dev/advanced/conditions',
  }
}

export const getCommandExamples = (deviceScreen: DeviceScreen, uiElement: UIElement): CommandExample[] => {
  const selectors = getSelectors(uiElement, deviceScreen)
  const commands: CommandExample[] = [
    ...selectors.map(toTapExample),
    toTapExample(getCoordinatesSelector(deviceScreen.width, deviceScreen.height, uiElement)),
    ...selectors.map(toAssertExample),
    ...selectors.map(toConditionalExample)
  ]
  return [
    ...commands.filter(c => c.status === 'available'),
    ...commands.filter(c => c.status === 'unavailable')
  ]
}
