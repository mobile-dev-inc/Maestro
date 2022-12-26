import ReplView from './ReplView';

export default {
  title: 'ReplView'
}

export const Main = () => {
  return (
    <ReplView onError={e => console.log(e)}/>
  )
}
