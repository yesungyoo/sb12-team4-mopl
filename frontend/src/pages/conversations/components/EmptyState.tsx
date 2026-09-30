import ilChatIcon from '@/assets/il_chat.svg';

export default function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center h-full gap-5">
      <div className="w-[120px] h-[120px]">
        <img src={ilChatIcon} alt="" className="w-full h-full" />
      </div>
      <p className="text-title1-b text-gray-400">모두에게 메시지를 보내보세요</p>
    </div>
  );
}
