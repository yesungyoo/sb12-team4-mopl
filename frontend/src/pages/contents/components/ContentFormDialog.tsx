import { useState, useEffect, type KeyboardEvent } from 'react';
import { toast } from 'sonner';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { createContent, updateContent } from '@/lib/api/contents';
import useContentStore from '@/lib/stores/useContentStore';
import type { ContentDto, ContentType, ContentCreateRequest, ContentUpdateRequest } from '@/lib/types';
import icX from '@/assets/ic_X.svg';

const ContentTypeLabel: Record<ContentType, string> = {
  movie: '영화',
  tvSeries: 'TV 시리즈',
  sport: '스포츠',
};

interface ContentFormDialogProps {
  mode: 'create' | 'edit';
  open: boolean;
  onOpenChange: (open: boolean) => void;
  initialData?: ContentDto;
}

export default function ContentFormDialog({ mode, open, onOpenChange, initialData }: ContentFormDialogProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<ContentType>('movie');
  const [tagInput, setTagInput] = useState('');
  const [tagList, setTagList] = useState<string[]>([]);
  const [thumbnail, setThumbnail] = useState<File | null>(null);
  const [thumbnailPreview, setThumbnailPreview] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);

  // Initialize form with initialData for edit mode
  useEffect(() => {
    if (mode === 'edit' && initialData) {
      setTitle(initialData.title);
      setDescription(initialData.description || '');
      setType(initialData.type);
      setTagList(initialData.tags || []);
      setTagInput('');
      setThumbnailPreview(initialData.thumbnailUrl || '');
    } else {
      // Reset form for create mode
      setTitle('');
      setDescription('');
      setType('movie');
      setTagList([]);
      setTagInput('');
      setThumbnail(null);
      setThumbnailPreview('');
    }
  }, [mode, initialData, open]);

  const handleThumbnailChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      setThumbnail(file);
      const reader = new FileReader();
      reader.onloadend = () => {
        setThumbnailPreview(reader.result as string);
      };
      reader.readAsDataURL(file);
    }
  };

  const handleAddTag = () => {
    const trimmedTag = tagInput.trim();
    if (trimmedTag && !tagList.includes(trimmedTag)) {
      setTagList([...tagList, trimmedTag]);
      setTagInput('');
    }
  };

  const handleRemoveTag = (tagToRemove: string) => {
    setTagList(tagList.filter(tag => tag !== tagToRemove));
  };

  const handleTagKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    // Support Korean IME composition
    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleAddTag();
    }
  };

  const validateForm = (): boolean => {
    if (!title.trim()) {
      toast.error('제목을 입력해주세요.');
      return false;
    }
    if (!description.trim()) {
      toast.error('설명을 입력해주세요.');
      return false;
    }
    if (mode === 'create' && !thumbnail) {
      toast.error('썸네일 이미지를 선택해주세요.');
      return false;
    }
    return true;
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    setSubmitting(true);
    try {
      if (mode === 'create') {
        const data: ContentCreateRequest = {
          title: title.trim(),
          description: description.trim(),
          type,
          tags: tagList,
        };

        // Call API
        const newContent = await createContent(data, thumbnail!);

        // Sync store
        useContentStore.getState().add(newContent);

        toast.success('콘텐츠가 등록되었습니다.');
        onOpenChange(false);
      } else {
        // Edit mode
        const data: ContentUpdateRequest = {
          title: title.trim(),
          description: description.trim(),
          tags: tagList,
        };

        // Call API
        const updatedContent = await updateContent(initialData!.id, data, thumbnail || undefined);

        // Sync store
        useContentStore.getState().update(initialData!.id, updatedContent);

        toast.success('콘텐츠가 수정되었습니다.');
        onOpenChange(false);
      }
    } catch (error) {
      const message = mode === 'create' ? '콘텐츠 등록에 실패했습니다.' : '콘텐츠 수정에 실패했습니다.';
      toast.error(message);
      console.error(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent hideCloseButton className="max-w-[520px] bg-gray-800/50 backdrop-blur-[25px] border border-gray-800 rounded-3xl p-9 max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-title1-sb text-gray-50">
            {mode === 'create' ? '콘텐츠 등록' : '콘텐츠 수정'}
          </h2>
          <button
            onClick={() => onOpenChange(false)}
            className="flex items-center justify-center w-8 h-8"
          >
            <img src={icX} alt="닫기" className="w-6 h-6" />
          </button>
        </div>

        {/* Form */}
        <div className="flex flex-col gap-6">
          {/* Thumbnail Upload */}
          <div className="flex flex-col gap-2">
            <Label htmlFor="thumbnail" className="text-body2-sb text-gray-300">
              썸네일 이미지 {mode === 'create' && <span className="text-pink-500">*</span>}
            </Label>
            {thumbnailPreview && (
              <div className="relative w-[200px] h-[300px] rounded-2xl overflow-hidden mb-2 mx-auto">
                <img
                  src={thumbnailPreview}
                  alt="썸네일 미리보기"
                  className="w-full h-full object-cover"
                />
              </div>
            )}
            <Input
              id="thumbnail"
              type="file"
              accept="image/*"
              onChange={handleThumbnailChange}
              className="bg-gray-700 border-gray-700 text-gray-300"
            />
          </div>

          {/* Title */}
          <div className="flex flex-col gap-2">
            <Label htmlFor="title" className="text-body2-sb text-gray-300">
              제목 <span className="text-pink-500">*</span>
            </Label>
            <Input
              id="title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="제목을 입력해주세요"
              className="bg-gray-700 border-gray-700 text-gray-50 placeholder:text-gray-500"
            />
          </div>

          {/* Description */}
          <div className="flex flex-col gap-2">
            <Label htmlFor="description" className="text-body2-sb text-gray-300">
              설명 <span className="text-pink-500">*</span>
            </Label>
            <textarea
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="설명을 입력해주세요"
              rows={4}
              className="flex w-full rounded-md border border-gray-700 bg-gray-700 px-3 py-2 text-body2-m text-gray-50 placeholder:text-gray-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-pink-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 resize-none"
            />
          </div>

          {/* Content Type - Only for create mode */}
          {mode === 'create' && (
            <div className="flex flex-col gap-2">
              <Label htmlFor="type" className="text-body2-sb text-gray-300">
                콘텐츠 유형 <span className="text-pink-500">*</span>
              </Label>
              <Select value={type} onValueChange={(value) => setType(value as ContentType)}>
                <SelectTrigger className="bg-gray-700 border-gray-700 text-gray-50 hover:bg-gray-600">
                  <SelectValue placeholder="콘텐츠 유형 선택" />
                </SelectTrigger>
                <SelectContent className="bg-gray-700 border-gray-600">
                  <SelectItem value="movie" className="text-gray-50 focus:bg-gray-600 focus:text-gray-50">
                    {ContentTypeLabel.movie}
                  </SelectItem>
                  <SelectItem value="tvSeries" className="text-gray-50 focus:bg-gray-600 focus:text-gray-50">
                    {ContentTypeLabel.tvSeries}
                  </SelectItem>
                  <SelectItem value="sport" className="text-gray-50 focus:bg-gray-600 focus:text-gray-50">
                    {ContentTypeLabel.sport}
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}

          {/* Content Type - Display only for edit mode */}
          {mode === 'edit' && (
            <div className="flex flex-col gap-2">
              <Label className="text-body2-sb text-gray-300">
                콘텐츠 유형
              </Label>
              <div className="px-3 py-2 rounded-md border border-gray-700 bg-gray-800 text-gray-400 text-body2-m">
                {ContentTypeLabel[type]}
              </div>
            </div>
          )}

          {/* Tags */}
          <div className="flex flex-col gap-2">
            <Label htmlFor="tags" className="text-body2-sb text-gray-300">
              태그
            </Label>

            {/* Tag Chips Display */}
            {tagList.length > 0 && (
              <div className="flex flex-wrap gap-2 mb-2">
                {tagList.map((tag) => (
                  <div
                    key={tag}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-pink-500/20 border border-pink-500/30"
                  >
                    <span className="text-body3-m text-pink-400">{tag}</span>
                    <button
                      type="button"
                      onClick={() => handleRemoveTag(tag)}
                      className="flex items-center justify-center w-4 h-4 rounded-full hover:bg-pink-500/30 transition-colors"
                      aria-label={`${tag} 태그 제거`}
                    >
                      <span className="text-pink-400 text-xs leading-none">×</span>
                    </button>
                  </div>
                ))}
              </div>
            )}

            {/* Tag Input */}
            <div className="flex gap-2">
              <Input
                id="tags"
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={handleTagKeyDown}
                placeholder="태그 입력 후 Enter"
                className="flex-1 bg-gray-700 border-gray-700 text-gray-50 placeholder:text-gray-500"
              />
              <Button
                type="button"
                onClick={handleAddTag}
                disabled={!tagInput.trim()}
                className="bg-gray-700 text-gray-300 hover:bg-gray-600 border border-gray-700"
                variant="outline"
              >
                추가
              </Button>
            </div>
          </div>

          {/* Action Buttons */}
          <div className="flex gap-3 pt-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={submitting}
              className="flex-1 bg-transparent border-gray-700 text-gray-300 hover:bg-gray-700"
            >
              취소
            </Button>
            <Button
              onClick={handleSubmit}
              disabled={submitting}
              className="flex-1 bg-pink-500 text-white hover:bg-pink-600"
            >
              {submitting ? '처리 중...' : mode === 'create' ? '등록' : '수정'}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
