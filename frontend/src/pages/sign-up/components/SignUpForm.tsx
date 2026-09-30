import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { createUser } from '@/lib/api/users';
import type { UserCreateRequest } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { useState } from 'react';

interface SignUpFormData {
  email: string;
  name: string;
  password: string;
  passwordConfirmation: string;
}

export default function SignUpForm() {
  const navigate = useNavigate();
  const [isLoading, setIsLoading] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
    watch,
  } = useForm<SignUpFormData>();

  const password = watch('password');

  const onSubmit = async (data: SignUpFormData) => {
    setIsLoading(true);

    try {
      const userData: UserCreateRequest = {
        email: data.email,
        name: data.name,
        password: data.password,
      };

      await createUser(userData);
      toast.success('회원가입이 완료되었습니다');
      navigate('/sign-in');
    } catch (error) {
      console.error("Fail to sign up:", error);
      toast.error('회원가입에 실패했습니다. 다시 시도해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="flex w-full flex-col gap-5">
      {/* Email Field */}
      <div className="flex w-full flex-col gap-2.5">
        <Label htmlFor="email" className="px-1 text-body3-sb text-gray-500">
          이메일
        </Label>
        <div className="flex w-full flex-col gap-1.5">
          <Input
            id="email"
            type="email"
            placeholder="이메일 입력"
            className={cn(
              'h-[54px] rounded-xl border-[1.5px]',
              errors.email ? 'border-[#c93c3f]' : 'border-gray-800',
              'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
            )}
            {...register('email', {
              required: '이메일을 입력해주세요',
              pattern: {
                value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
                message: '올바른 이메일 형식이 아닙니다',
              },
            })}
            disabled={isLoading}
          />
          {errors.email && (
            <p className="px-2 text-body3-m text-red-notification">{errors.email.message}</p>
          )}
        </div>
      </div>

      {/* Name Field */}
      <div className="flex w-full flex-col gap-2.5">
        <Label htmlFor="name" className="px-1 text-body3-sb text-gray-500">
          이름
        </Label>
        <div className="flex w-full flex-col gap-1.5">
          <Input
            id="name"
            type="text"
            placeholder="이름 입력"
            className={cn(
              'h-[54px] rounded-xl border-[1.5px]',
              errors.name ? 'border-[#c93c3f]' : 'border-gray-800',
              'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
            )}
            {...register('name', {
              required: '이름을 입력해주세요',
              minLength: {
                value: 2,
                message: '이름은 최소 2자 이상이어야 합니다',
              },
            })}
            disabled={isLoading}
          />
          {errors.name && (
            <p className="px-2 text-body3-m text-red-notification">{errors.name.message}</p>
          )}
        </div>
      </div>

      {/* Password Field */}
      <div className="flex w-full flex-col gap-2.5">
        <Label htmlFor="password" className="px-1 text-body3-sb text-gray-500">
          비밀번호
        </Label>
        <div className="flex w-full flex-col gap-1.5">
          <Input
            id="password"
            type="password"
            placeholder="비밀번호 입력"
            className={cn(
              'h-[54px] rounded-xl border-[1.5px]',
              errors.password ? 'border-[#c93c3f]' : 'border-gray-800',
              'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
            )}
            {...register('password', {
              required: '비밀번호를 입력해주세요',
              minLength: {
                value: 8,
                message: '비밀번호는 최소 8자 이상이어야 합니다',
              },
            })}
            disabled={isLoading}
          />
          {errors.password && (
            <p className="px-2 text-body3-m text-red-notification">{errors.password.message}</p>
          )}
        </div>
      </div>

      {/* Password Confirmation Field */}
      <div className="flex w-full flex-col gap-2.5">
        <Label htmlFor="passwordConfirmation" className="px-1 text-body3-sb text-gray-500">
          비밀번호 확인
        </Label>
        <div className="flex w-full flex-col gap-1.5">
          <Input
            id="passwordConfirmation"
            type="password"
            placeholder="한 번 더 입력해주세요"
            className={cn(
              'h-[54px] rounded-xl border-[1.5px]',
              errors.passwordConfirmation ? 'border-[#c93c3f]' : 'border-gray-800',
              'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
            )}
            {...register('passwordConfirmation', {
              required: '비밀번호 확인을 입력해주세요',
              validate: (value) => value === password || '비밀번호가 일치하지 않습니다',
            })}
            disabled={isLoading}
          />
          {errors.passwordConfirmation && (
            <p className="px-2 text-body3-m text-red-notification">
              {errors.passwordConfirmation.message}
            </p>
          )}
        </div>
      </div>

      {/* Submit Button */}
      <div className="w-full pt-3">
        <Button
          type="submit"
          disabled={isLoading}
          className="h-[54px] w-full rounded-xl bg-pink-500 text-body1-b text-white hover:bg-pink-600 disabled:bg-gray-800 disabled:text-gray-600"
        >
          {isLoading ? '가입 중...' : '가입하기'}
        </Button>
      </div>
    </form>
  );
}
