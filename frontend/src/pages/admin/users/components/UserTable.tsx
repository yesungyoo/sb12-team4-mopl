import type { UserDto, UserRole, SortDirection } from '@/lib/types';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import LockButton from './LockButton';
import RoleToggle from './RoleToggle';
import SortableHeader from './SortableHeader';

type SortBy = 'name' | 'email' | 'createdAt' | 'isLocked' | 'role';

interface UserTableProps {
  users: UserDto[];
  onLockToggle: (userId: string, currentLocked: boolean) => void;
  onRoleToggle: (userId: string, currentRole: UserRole) => void;
  loadingUserId?: string;
  sortBy?: SortBy;
  sortDirection?: SortDirection;
  onSort: (sortBy: SortBy) => void;
}

function formatDate(dateString: string): string {
  const date = new Date(dateString);
  const year = date.getFullYear();
  const month = date.getMonth() + 1;
  const day = date.getDate();
  return `${year}. ${month}. ${day}`;
}

interface TableHeaderComponentProps {
  sortBy?: SortBy;
  sortDirection?: SortDirection;
  onSort: (sortBy: SortBy) => void;
}

function TableHeaderComponent({
  sortBy,
  sortDirection,
  onSort,
}: TableHeaderComponentProps) {
  return (
    <TableHeader>
      <TableRow className="border-gray-800 hover:bg-transparent">
        <TableHead className="w-[200px]">
          <SortableHeader
            label="이름"
            sortKey="name"
            currentSortBy={sortBy}
            currentSortDirection={sortDirection}
            onSort={onSort}
          />
        </TableHead>
        <TableHead className="w-[200px]">
          <SortableHeader
            label="이메일"
            sortKey="email"
            currentSortBy={sortBy}
            currentSortDirection={sortDirection}
            onSort={onSort}
          />
        </TableHead>
        <TableHead className="w-[140px]">
          <SortableHeader
            label="가입일"
            sortKey="createdAt"
            currentSortBy={sortBy}
            currentSortDirection={sortDirection}
            onSort={onSort}
          />
        </TableHead>
        <TableHead className="w-[140px]">
          <SortableHeader
            label="계정 잠금"
            sortKey="isLocked"
            currentSortBy={sortBy}
            currentSortDirection={sortDirection}
            onSort={onSort}
          />
        </TableHead>
        <TableHead className="w-[100px]">
          <SortableHeader
            label="ADMIN"
            sortKey="role"
            currentSortBy={sortBy}
            currentSortDirection={sortDirection}
            onSort={onSort}
          />
        </TableHead>
      </TableRow>
    </TableHeader>
  );
}

interface TableRowComponentProps {
  user: UserDto;
  onLockToggle: (userId: string, currentLocked: boolean) => void;
  onRoleToggle: (userId: string, currentRole: UserRole) => void;
  isLoading: boolean;
}

function TableRowComponent({
  user,
  onLockToggle,
  onRoleToggle,
  isLoading,
}: TableRowComponentProps) {
  // All UserDto fields are optional in API spec, but in reality they're always present
  if (!user.id || !user.createdAt) {
    return null;
  }

  return (
    <TableRow className="border-gray-800">
      <TableCell className="w-[200px] text-body2-m text-gray-200">
        {user.name ?? '-'}
      </TableCell>
      <TableCell className="w-[200px] text-body2-m text-gray-100">
        {user.email ?? '-'}
      </TableCell>
      <TableCell className="w-[140px] text-body2-m text-gray-100">
        {formatDate(user.createdAt)}
      </TableCell>
      <TableCell className="w-[140px]">
        <LockButton
          locked={user.locked ?? false}
          onClick={() => onLockToggle(user.id!, user.locked ?? false)}
          loading={isLoading}
          disabled={isLoading}
        />
      </TableCell>
      <TableCell className="w-[100px]">
        <RoleToggle
          isAdmin={user.role === 'ADMIN'}
          onChange={() => onRoleToggle(user.id!, user.role ?? 'USER')}
          loading={isLoading}
          disabled={isLoading}
        />
      </TableCell>
    </TableRow>
  );
}

function EmptyState() {
  return (
    <div className="flex min-h-[400px] items-center justify-center">
      <p className="text-body2-m text-gray-400">사용자가 없습니다</p>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="space-y-0">
      {Array.from({ length: 5 }).map((_, index) => (
        <div
          key={index}
          className="flex animate-pulse items-center gap-[100px] border-b border-gray-800 px-0 py-[10px]"
        >
          <div className="h-5 w-[200px] rounded bg-gray-800" />
          <div className="h-5 w-[200px] rounded bg-gray-800" />
          <div className="h-5 w-[140px] rounded bg-gray-800" />
          <div className="h-5 w-[140px] rounded bg-gray-800" />
          <div className="h-[34px] w-[68px] rounded-lg bg-gray-800" />
          <div className="h-[25px] w-[50px] rounded-full bg-gray-800" />
        </div>
      ))}
    </div>
  );
}

export default function UserTable({
  users,
  onLockToggle,
  onRoleToggle,
  loadingUserId,
  sortBy,
  sortDirection,
  onSort,
}: UserTableProps) {
  if (users.length === 0) {
    return (
      <>
        <Table>
          <TableHeaderComponent
            sortBy={sortBy}
            sortDirection={sortDirection}
            onSort={onSort}
          />
        </Table>
        <EmptyState />
      </>
    );
  }

  return (
    <Table>
      <TableHeaderComponent
        sortBy={sortBy}
        sortDirection={sortDirection}
        onSort={onSort}
      />
      <TableBody>
        {users.map((user) => (
          <TableRowComponent
            key={user.id}
            user={user}
            onLockToggle={onLockToggle}
            onRoleToggle={onRoleToggle}
            isLoading={loadingUserId === user.id}
          />
        ))}
      </TableBody>
    </Table>
  );
}

export { LoadingSkeleton };
