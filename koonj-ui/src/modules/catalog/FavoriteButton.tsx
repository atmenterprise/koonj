import { Button } from '../../shared/ui/primitives'

export function FavoriteButton({ name, favorited, disabled, onClick }: {
  name: string; favorited: boolean; disabled?: boolean; onClick: () => void
}) {
  return <Button icon="star" variant="quiet" className="icon-button catalog-favorite-button"
    aria-label={`Favorite ${name}`} aria-pressed={favorited} disabled={disabled}
    title={favorited ? 'Remove from favorites' : 'Add to favorites'} onClick={onClick} />
}
