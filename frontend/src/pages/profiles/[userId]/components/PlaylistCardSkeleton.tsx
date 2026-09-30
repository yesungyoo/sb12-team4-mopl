export default function PlaylistCardSkeleton() {
  return (
      <div className="w-full h-[250px] rounded-[24px] bg-gray-800 animate-pulse border border-gray-700">
        <div className="p-8 flex items-start justify-between h-full">
          <div className="flex-1 flex flex-col justify-between h-full pr-6">
            <div>
              <div className="h-7 bg-gray-700 rounded-md w-48 mb-5" />
              <div className="h-5 bg-gray-700 rounded-md w-64 mb-2" />
              <div className="h-5 bg-gray-700 rounded-md w-32" />
            </div>
            <div className="flex items-center gap-2">
              <div className="h-4 bg-gray-700 rounded-md w-20" />
              <div className="h-4 bg-gray-700 rounded-md w-16" />
            </div>
          </div>
          <div className="w-[130px] h-[190px] bg-gray-700 rounded-[16px]" />
        </div>
      </div>
  );
}
